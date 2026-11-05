package kr.ac.chosun.mspace.assistant.agent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.mcp.ProposalParser;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentLoop;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ToolGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmReply;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolCall;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 에이전트 반복 (가짜 모델 + 가짜 도구). 진짜 모델 성공률 측정은 #9에서 키로 한다 */
class AgentLoopTest {

    static final UserHeaders ME = new UserHeaders(Map.of("X-User-Id", "1"));
    /** 2026-11-02(월) 15:00 서울 */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T06:00:00Z"), ZoneId.of("UTC"));

    /** 정해 둔 답을 차례로 내고, 받은 대화·지시문을 기록하는 모델 */
    static class ScriptedLlm implements LlmClient {
        final Deque<LlmReply> replies = new ArrayDeque<>();
        final List<List<LlmMessage>> seen = new ArrayList<>();
        String lastSystem;

        ScriptedLlm then(LlmReply reply) {
            replies.add(reply);
            return this;
        }

        @Override
        public LlmReply chat(String system, List<LlmMessage> conversation, List<ToolSpec> tools) {
            lastSystem = system;
            seen.add(List.copyOf(conversation));
            return replies.isEmpty() ? new LlmReply("(끝)", List.of()) : replies.poll();
        }
    }

    /** 도구 이름 → 정해 둔 결과. 부른 순서를 기록한다 */
    static class FakeTools implements ToolGateway {
        final Map<String, Outcome> results;
        final List<String> called = new ArrayList<>();
        boolean closed;

        FakeTools(Map<String, Outcome> results) {
            this.results = results;
        }

        @Override
        public Session open(UserHeaders headers) {
            return new Session() {
                @Override
                public List<ToolSpec> tools() {
                    return results.keySet().stream().map(n -> new ToolSpec(n, n, Map.of("type", "object"))).toList();
                }

                @Override
                public Outcome call(String name, Map<String, Object> arguments) {
                    called.add(name);
                    // 진짜 어댑터처럼 mcp-server 원문을 ProposalParser에 통과시킨다
                    Outcome raw = results.getOrDefault(name, new Outcome("{\"message\":\"없는 도구\"}", true));
                    return ProposalParser.parse(name, raw.json(), raw.isError(), JSON);
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }
    }

    static LlmReply tool(String name, Map<String, Object> args) {
        return new LlmReply("", List.of(new ToolCall("c-" + name, name, args)));
    }

    static LlmReply say(String text) {
        return new LlmReply(text, List.of());
    }

    static final JsonMapper JSON = JsonMapper.builder().build();

    static AgentLoop loop(LlmClient llm, ToolGateway tools) {
        return loop(llm, tools, null);
    }

    static AgentLoop loop(LlmClient llm, ToolGateway tools, AgentProperties.Rules rules) {
        return new AgentLoop(llm, tools, new AgentProperties(null, null, 8, null, rules, null, 0, 0), CLOCK);
    }

    static final String FREE = "{\"date\":\"2026-11-03\",\"rooms\":[{\"roomId\":2,\"free\":[]}],\"note\":null}";
    static final String PROPOSAL = """
            {"confirmToken":"c_secret","expiresAt":"2026-11-02T15:02:00+09:00",
             "summary":"2번 방, 11월 3일(화) 14:00–16:00, 3명으로 예약할까요?","notes":["함께 쓰는 2명의 학번은 확인 화면에서 입력해요."],
             "needsParticipants":2}""";

    @Test
    @DisplayName("도구가 필요 없으면 모델 답을 그대로, 도구는 부르지 않는다")
    void noTool() {
        var llm = new ScriptedLlm().then(say("안녕하세요"));
        var tools = new FakeTools(Map.of());

        ChatResult result = loop(llm, tools).run(ME, List.of(), "안녕");

        assertThat(result.reply()).isEqualTo("안녕하세요");
        assertThat(result.toolCalls()).isZero();
        assertThat(tools.closed).isTrue();
    }

    @Test
    @DisplayName("시나리오 '내일 오후 2시 빈방': 도구 결과를 붙여 다시 묻고, 도구 호출이 없으면 끝")
    void findThenAnswer() {
        var llm = new ScriptedLlm()
                .then(tool("find_available_rooms", Map.of("date", "2026-11-03", "from", "14:00")))
                .then(say("2번 방이 비어 있어요"));
        var tools = new FakeTools(Map.of("find_available_rooms", new ToolGateway.Outcome(FREE, false)));

        ChatResult result = loop(llm, tools).run(ME, List.of(), "내일 오후 2시 빈방 있어?");

        assertThat(result.reply()).isEqualTo("2번 방이 비어 있어요\n\n(예약 시스템 15:00 조회 기준)");
        assertThat(tools.called).containsExactly("find_available_rooms");
        assertThat(result.tools()).containsExactly("find_available_rooms"); // 측정(#9)이 이걸로 도구 선택을 판정한다
        assertThat(llm.seen.get(1).getLast()).isInstanceOfSatisfying(LlmMessage.ToolResult.class,
                r -> assertThat(r.json()).contains("\"roomId\":2"));
    }

    @Test
    @DisplayName("도구를 부른 답에만 조회 시각 꼬리말이 붙는다. 도구 없는 답(인사)에는 안 붙는다 (TS-09)")
    void groundsOnlyToolBackedReplies() {
        var withTool = new ScriptedLlm()
                .then(tool("find_available_rooms", Map.of("date", "2026-11-03")))
                .then(say("다 비어 있어요  "));
        var plain = new ScriptedLlm().then(say("안녕하세요"));
        var tools = new FakeTools(Map.of("find_available_rooms", new ToolGateway.Outcome(FREE, false)));

        ChatResult grounded = loop(withTool, tools).run(ME, List.of(), "오늘 빈방");
        ChatResult greeting = loop(plain, tools).run(ME, List.of(), "안녕");

        assertThat(grounded.reply()).isEqualTo("다 비어 있어요\n\n(예약 시스템 15:00 조회 기준)");
        assertThat(greeting.reply()).isEqualTo("안녕하세요");
    }

    @Test
    @DisplayName("도구가 실패만 했으면 조회한 게 아니므로 꼬리말을 붙이지 않는다")
    void noGroundingWhenOnlyFailedTools() {
        // given
        var llm = new ScriptedLlm()
                .then(tool("find_available_rooms", Map.of("date", "2026-11-03")))
                .then(say("지금은 확인할 수 없어요"));
        var tools = new FakeTools(Map.of("find_available_rooms",
                new ToolGateway.Outcome("{\"message\":\"도구를 실행하지 못했어요.\"}", true)));

        // when
        ChatResult result = loop(llm, tools).run(ME, List.of(), "오늘 빈방");

        // then
        assertThat(result.reply()).isEqualTo("지금은 확인할 수 없어요");
        assertThat(result.toolCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("지시문: 가린 표시([학번]) 설명, 본인 예약만, 인원 모르면 headcount 생략, 앞선 답은 조회 결과, 미루는 말 금지 (TS-09·TS-10)")
    void systemPromptCoversDemoFeedback() {
        var llm = new ScriptedLlm().then(say("네"));

        loop(llm, new FakeTools(Map.of())).run(ME, List.of(), "20233121 예약 있어?");

        assertThat(llm.lastSystem)
                .contains("[학번]·[전화번호]·[입실 번호]는 개인정보를 가린 표시")
                .contains("my_reservations는 로그인한 학생 본인의 예약만")
                .contains("인원을 말하지 않았으면 headcount를 넣지 않는다")
                .contains("꼬리말이 붙은 것은 그 시각에 도구로 조회한 결과")
                .contains("꼬리말이 없는 답은 조회한 것이 아니니")
                .contains("확인하지 않았다고 사과하지 않는다")
                .contains("\"잠시만 기다려 주세요\"")
                .contains("note·message 문장은 바꿔 말하지 않고");
        assertThat(llm.lastSystem).contains("room_status로 지금 비어 있는 방을 보고", "start_now_reservation으로 제안한다");
    }

    @Test
    @DisplayName("반복 최대 횟수 초과: 도구를 8번 부른 뒤 9번째 요청에서 멈추고 알린다")
    void stopsAtLimit() {
        var llm = new ScriptedLlm();
        for (int i = 0; i < 20; i++) {
            llm.then(tool("my_reservations", Map.of()));
        }
        var tools = new FakeTools(Map.of("my_reservations", new ToolGateway.Outcome("{\"reservations\":[]}", false)));

        ChatResult result = loop(llm, tools).run(ME, List.of(), "계속 찾아 줘");

        assertThat(result.stoppedByLimit()).isTrue();
        assertThat(result.suggestions()).as("멈춘 대화엔 빠른 답장 없음").isEmpty();
        assertThat(result.toolCalls()).isEqualTo(8);
        assertThat(tools.called).hasSize(8);
        assertThat(result.reply()).contains("너무 많아져서 멈췄어요");
    }

    /** 읽을 때마다 정해 둔 만큼 흐르는 시계 (모델·도구가 느린 상황) */
    static class SlowClock extends Clock {
        Instant now = CLOCK.instant();
        final java.time.Duration step;

        SlowClock(java.time.Duration step) {
            this.step = step;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            Instant current = now;
            now = now.plus(step);
            return current;
        }
    }

    @Test
    @DisplayName("대화 전체 시간 상한(45초)을 넘으면 모델을 더 부르지 않고, 그때까지의 확인 카드와 함께 멈춘다")
    void stopsAtTimeLimit() {
        // given: 시계를 읽을 때마다 20초가 흐른다
        var llm = new ScriptedLlm()
                .then(tool("create_reservation", Map.of("roomId", 2)))
                .then(tool("my_reservations", Map.of()))
                .then(tool("my_reservations", Map.of()))
                .then(say("다 했어요"));
        var tools = new FakeTools(Map.of(
                "create_reservation", new ToolGateway.Outcome(PROPOSAL, false),
                "my_reservations", new ToolGateway.Outcome("{\"reservations\":[]}", false)));
        var loop = new AgentLoop(llm, tools, new AgentProperties(null, null, 8, null, null, null, 0, 0),
                new SlowClock(java.time.Duration.ofSeconds(20)));

        // when
        ChatResult result = loop.run(ME, List.of(), "내일 2시 2번 방 잡아 줘");

        // then
        assertThat(result.stoppedByLimit()).isTrue();
        assertThat(result.reply()).contains("시간이 너무 오래 걸려서");
        assertThat(result.confirmations()).hasSize(1);
        assertThat(llm.seen.size()).isLessThan(4);
    }

    @Test
    @DisplayName("한 답에 도구가 여러 개여도, 도구를 부르기 전마다 시간 상한을 보고 넘었으면 남은 도구를 부르지 않는다")
    void stopsBetweenToolCallsAtTimeLimit() {
        // given: 시계를 읽을 때마다 20초가 흐르고, 모델이 한 번에 도구 3개를 부른다
        var llm = new ScriptedLlm().then(new LlmReply("", List.of(
                new ToolCall("c1", "create_reservation", Map.of("roomId", 2)),
                new ToolCall("c2", "my_reservations", Map.of()),
                new ToolCall("c3", "my_reservations", Map.of()))));
        var tools = new FakeTools(Map.of(
                "create_reservation", new ToolGateway.Outcome(PROPOSAL, false),
                "my_reservations", new ToolGateway.Outcome("{\"reservations\":[]}", false)));
        var loop = new AgentLoop(llm, tools, new AgentProperties(null, null, 8, null, null, null, 0, 0),
                new SlowClock(java.time.Duration.ofSeconds(20)));

        // when
        ChatResult result = loop.run(ME, List.of(), "내일 2시 2번 방 잡아 줘");

        // then: 20초·40초에는 부르고, 60초(상한 45초 넘음)에는 멈춘다. 먼저 만든 카드는 그대로 간다
        assertThat(tools.called).hasSize(2);
        assertThat(result.stoppedByLimit()).isTrue();
        assertThat(result.reply()).contains("시간이 너무 오래 걸려서");
        assertThat(result.confirmations()).hasSize(1);
        assertThat(llm.seen).hasSize(1);
    }

    @Test
    @DisplayName("확인 토큰에 숫자 6개가 이어져도 가리지 않고 원래 값으로 카드에 싣는다 (가리면 확인이 실패한다)")
    void keepsConfirmTokenUnmasked() {
        // given
        String proposal = PROPOSAL.replace("c_secret", "c_Ab123456cD");
        var llm = new ScriptedLlm()
                .then(tool("create_reservation", Map.of("roomId", 2)))
                .then(say("확인 카드를 보여 드렸어요"));
        var tools = new FakeTools(Map.of("create_reservation", new ToolGateway.Outcome(proposal, false)));

        // when
        ChatResult result = loop(llm, tools).run(ME, List.of(), "내일 2시 2번 방 잡아 줘");

        // then
        assertThat(result.confirmations()).singleElement()
                .satisfies(c -> assertThat(c.token()).isEqualTo("c_Ab123456cD"));
        assertThat(llm.seen.get(1).toString()).doesNotContain("c_Ab");
    }

    @Test
    @DisplayName("시나리오 '내 예약 취소해 줘': 내 예약 → 취소 제안 → 확인 카드. 모델은 확인 토큰을 보지 못한다")
    void cancelProposalBecomesCard() {
        var llm = new ScriptedLlm()
                .then(tool("my_reservations", Map.of()))
                .then(tool("cancel_reservation", Map.of("reservationId", 7)))
                .then(say("1번 방 예약을 취소할까요? 화면의 [확인]을 눌러 주세요."));
        var tools = new FakeTools(Map.of(
                "my_reservations", new ToolGateway.Outcome("{\"reservations\":[{\"id\":7,\"owner\":true}]}", false),
                "cancel_reservation", new ToolGateway.Outcome(PROPOSAL.replace("2번 방, 11월 3일(화) 14:00–16:00, 3명으로 예약할까요?",
                        "1번 방 11월 3일(화) 14:00–16:00 예약을 취소할까요?").replace("\"needsParticipants\":2", "\"needsParticipants\":0"), false)));

        ChatResult result = loop(llm, tools).run(ME, List.of(), "내 예약 취소해 줘");

        assertThat(tools.called).containsExactly("my_reservations", "cancel_reservation");
        assertThat(result.confirmations()).singleElement().satisfies(c -> {
            assertThat(c.token()).isEqualTo("c_secret");
            assertThat(c.tool()).isEqualTo("cancel_reservation");
            assertThat(c.summary()).contains("취소할까요?");
        });
        assertThat(llm.seen.getLast().toString()).doesNotContain("c_secret").contains("[확인] 카드");
    }

    @Test
    @DisplayName("예약 제안: 확인 카드에 받을 학번 수(needsParticipants)가 담긴다")
    void createProposal() {
        var llm = new ScriptedLlm()
                .then(tool("create_reservation", Map.of("roomId", 2)))
                .then(say("확인을 눌러 주세요"));
        var tools = new FakeTools(Map.of("create_reservation", new ToolGateway.Outcome(PROPOSAL, false)));

        ChatResult result = loop(llm, tools).run(ME, List.of(), "2번 방 잡아 줘");

        assertThat(result.confirmations()).singleElement().satisfies(c -> {
            assertThat(c.needsParticipants()).isEqualTo(2);
            assertThat(c.notes()).containsExactly("함께 쓰는 2명의 학번은 확인 화면에서 입력해요.");
        });
    }

    @Test
    @DisplayName("외부 AI로 보내기 전에 학번·전화번호(+82, 일반 전화 포함)를 가린다. 지난 대화의 비서 차례도")
    void masksPersonalInfo() {
        var llm = new ScriptedLlm().then(say("알겠어요"));

        loop(llm, new FakeTools(Map.of())).run(ME, List.of(new LlmMessage.User("내 번호는 010-1234-5678"),
                        new LlmMessage.Assistant("+82 10-9876-5432로 연락드릴게요", List.of())),
                "친구 학번 20231234도 넣어 줘. 학과 사무실은 062-230-1234, 해외에서는 +82 62-230-5678, +82-(0)62-230-9012, +82 (0)10-2222-3333");

        String sent = llm.seen.getFirst().toString();
        assertThat(sent).doesNotContain("20231234", "010-1234-5678", "9876-5432", "230-1234", "230-5678", "230-9012", "2222-3333").contains("[학번]", "[전화번호]");
    }

    @Test
    @DisplayName("시스템 지시문에 오늘 날짜(서울)를 넣어 '내일'을 날짜로 바꿀 수 있게 한다")
    void systemPromptHasToday() {
        var llm = new ScriptedLlm().then(say("네"));

        loop(llm, new FakeTools(Map.of())).run(ME, List.of(), "내일 빈방");

        assertThat(llm.lastSystem).contains("2026-11-02(월) 15:00").contains("[확인]");
    }

    /** my_schedule 결과: 2026-11-03(화) 마지막 수업 14:50 끝 → 15:30부터 */
    static final String TUESDAY = """
            {"date":"2026-11-03","dayOfWeek":"화","code":null,
             "classes":[{"name":"자료구조","startAt":"2026-11-03T13:00:00+09:00","endAt":"2026-11-03T14:50:00+09:00"}],
             "lastClassEndAt":"2026-11-03T14:50:00+09:00","searchFromAt":"2026-11-03T15:30:00+09:00",
             "bufferMinutes":30,"note":null}""";
    static final String NO_SCHEDULE = """
            {"date":"2026-11-03","dayOfWeek":"화","code":"NO_SCHEDULE","classes":[],"lastClassEndAt":null,
             "searchFromAt":null,"bufferMinutes":30,"note":"아직 넣은 시간표가 없어요. 화면의 [내 시간표]에서 넣어 주세요."}""";

    @Test
    @DisplayName("시나리오 '내일 수업 끝나고 2시간 잡아 줘': 되묻지 않고 my_schedule → find_available_rooms(from=searchFromAt) → 확인 카드")
    void classEndFromSchedule() {
        // given
        var llm = new ScriptedLlm()
                .then(tool("my_schedule", Map.of("date", "2026-11-03")))
                .then(tool("find_available_rooms", Map.of("date", "2026-11-03", "from", "15:30", "to", "17:30")))
                .then(tool("create_reservation", Map.of("roomId", 2, "startAt", "2026-11-03T15:30:00+09:00",
                        "endAt", "2026-11-03T17:30:00+09:00")))
                .then(say("2번 방, 15:30–17:30으로 예약할까요? 화면의 [확인]을 눌러 주세요"));
        var tools = new FakeTools(Map.of(
                "my_schedule", new ToolGateway.Outcome(TUESDAY, false),
                "find_available_rooms", new ToolGateway.Outcome(FREE, false),
                "create_reservation", new ToolGateway.Outcome(PROPOSAL, false)));

        // when
        ChatResult result = loop(llm, tools).run(ME, List.of(), "내일 수업 끝나고 2시간 잡아 줘");

        // then
        assertThat(llm.lastSystem).contains("먼저 my_schedule", "되묻지 않는다", "searchFromAt")
                .doesNotContain("수업 시간표는 아직 모른다");
        assertThat(tools.called).containsExactly("my_schedule", "find_available_rooms", "create_reservation");
        assertThat(llm.seen.get(1).getLast()).isInstanceOfSatisfying(LlmMessage.ToolResult.class,
                r -> assertThat(r.json()).contains("\"searchFromAt\":\"2026-11-03T15:30:00+09:00\""));
        assertThat(result.confirmations()).hasSize(1);
        assertThat(result.reply()).endsWith("(예약 시스템 15:00 조회 · 입력한 시간표 기준)");
    }

    @Test
    @DisplayName("시간표만 본 답은 '입력한 시간표 기준', 시간표가 없다는 답에는 시간표 꼬리말을 붙이지 않는다")
    void scheduleGrounding() {
        // given
        var seen = new ScriptedLlm().then(tool("my_schedule", Map.of())).then(say("오늘은 자료구조가 있어요"));
        var none = new ScriptedLlm().then(tool("my_schedule", Map.of())).then(say("아직 넣은 시간표가 없어요."));

        // when
        ChatResult withSchedule = loop(seen, new FakeTools(Map.of("my_schedule", new ToolGateway.Outcome(TUESDAY, false))))
                .run(ME, List.of(), "오늘 수업 뭐 있어?");
        ChatResult withoutSchedule = loop(none, new FakeTools(Map.of("my_schedule", new ToolGateway.Outcome(NO_SCHEDULE, false))))
                .run(ME, List.of(), "오늘 수업 뭐 있어?");

        // then
        assertThat(withSchedule.reply()).isEqualTo("오늘은 자료구조가 있어요\n\n(입력한 시간표 기준)");
        assertThat(withSchedule.hints()).isEmpty();
        assertThat(withoutSchedule.reply()).isEqualTo("아직 넣은 시간표가 없어요.");
        // 시간표가 없을 때는 화면이 [내 시간표] 버튼을 그리도록 신호를 준다 (설계 5장 ③)
        assertThat(withoutSchedule.hints()).containsExactly(ChatResult.HINT_OPEN_SCHEDULE);
        assertThat(withoutSchedule.suggestions()).as("시간표 없음은 버튼(hints)으로, 빠른 답장은 비움").isEmpty();
    }

    /** 2026-11-03(화) 15:30부터 빈 칸이 하나도 없음 */
    static final String FULL = "{\"date\":\"2026-11-03\",\"rooms\":[],\"note\":\"이 시간대에 빈방이 없어요.\"}";
    /** 2026-11-04(수) 마지막 수업 12:00 끝 → 12:30부터 */
    static final String WEDNESDAY = """
            {"date":"2026-11-04","dayOfWeek":"수","code":null,
             "classes":[{"name":"운영체제","startAt":"2026-11-04T10:00:00+09:00","endAt":"2026-11-04T12:00:00+09:00"}],
             "lastClassEndAt":"2026-11-04T12:00:00+09:00","searchFromAt":"2026-11-04T12:30:00+09:00",
             "bufferMinutes":30,"note":null}""";
    static final String FREE_WED = "{\"date\":\"2026-11-04\",\"rooms\":[{\"roomId\":3,\"free\":[{\"startAt\":\"2026-11-04T12:30:00+09:00\",\"endAt\":\"2026-11-04T15:00:00+09:00\"}]}],\"note\":null}";

    @Test
    @DisplayName("장면 C '수업 끝나고 빈방 잡아 줘' - 오늘 없으면 되묻지 않고 다음 날 시간표 → 빈방 순으로 이어서 본다")
    void sceneCFallsBackToNextDay() {
        // given: 모델이 지시문대로 화요일 → 없음 → 수요일 시간표 → 수요일 빈방 순으로 부른다
        var llm = new ScriptedLlm()
                .then(tool("my_schedule", Map.of("date", "2026-11-03")))
                .then(tool("find_available_rooms", Map.of("date", "2026-11-03", "from", "15:30")))
                .then(tool("my_schedule", Map.of("date", "2026-11-04")))
                .then(tool("find_available_rooms", Map.of("date", "2026-11-04", "from", "12:30")))
                .then(say("내일은 없어서 모레(수) 기준으로 찾았어요. 3번 방이 12:30부터 비어 있어요"));
        var byCall = new ArrayDeque<>(List.of(TUESDAY, FULL, WEDNESDAY, FREE_WED));
        var tools = new FakeTools(Map.of()) {
            @Override
            public Session open(UserHeaders headers) {
                Session inner = super.open(headers);
                return new Session() {
                    @Override
                    public List<ToolSpec> tools() {
                        return List.of(new ToolSpec("my_schedule", "", Map.of()), new ToolSpec("find_available_rooms", "", Map.of()));
                    }

                    @Override
                    public Outcome call(String name, Map<String, Object> arguments) {
                        called.add(name);
                        return ProposalParser.parse(name, byCall.poll(), false, JSON);
                    }

                    @Override
                    public void close() {
                        inner.close();
                    }
                };
            }
        };

        // when
        ChatResult result = loop(llm, tools).run(ME, List.of(), "내일 수업 끝나고 빈방 잡아 줘");

        // then
        assertThat(llm.lastSystem).contains("다음 날을 이어서 본다", "사흘째까지 혼자 넘어가지 않는다");
        assertThat(tools.called).containsExactly("my_schedule", "find_available_rooms", "my_schedule", "find_available_rooms");
        assertThat(result.tools()).hasSize(4);
        assertThat(result.hints()).isEmpty();
        assertThat(result.reply()).startsWith("내일은 없어서").endsWith("(예약 시스템 15:00 조회 · 입력한 시간표 기준)");
    }

    @Test
    @DisplayName("지시문의 예약 규칙 숫자는 설정값에서 온다. 없으면 30분·2시간·7일")
    void promptRulesFromConfig() {
        var defaults = new ScriptedLlm().then(say("네"));
        var custom = new ScriptedLlm().then(say("네"));

        loop(defaults, new FakeTools(Map.of())).run(ME, List.of(), "빈방");
        loop(custom, new FakeTools(Map.of()), new AgentProperties.Rules(60, 3, 14)).run(ME, List.of(), "빈방");

        assertThat(defaults.lastSystem).contains("30분 단위, 1회 최대 2시간, 시작이 오늘부터 7일 안");
        assertThat(custom.lastSystem).contains("60분 단위, 1회 최대 3시간, 시작이 오늘부터 14일 안");
    }

    @Test
    @DisplayName("오류 결과·확인 토큰 없는 결과·JSON 아닌 결과는 카드 없이 그대로 모델에 간다")
    void parserLeavesOthersAlone() {
        assertThat(ProposalParser.parse("create_reservation", PROPOSAL, true, JSON).confirmation()).isEmpty();
        assertThat(ProposalParser.parse("my_reservations", FREE, false, JSON))
                .satisfies(o -> assertThat(o.confirmation()).isEmpty())
                .satisfies(o -> assertThat(o.json()).isEqualTo(FREE));
        assertThat(ProposalParser.parse("x", "not json", false, JSON).json()).isEqualTo("not json");
    }

    @Test
    @DisplayName("시나리오 '입실 번호 알려 줘'(대표자): 화면 카드만 만들고 모델 입력에는 번호가 없다")
    void ownerAccessCodeBecomesCard() {
        var llm = new ScriptedLlm().then(tool("show_access_code", Map.of())).then(say("화면 카드에서 확인해 주세요"));
        // 혹시 mcp-server가 번호를 실어 보내도 지워지는지까지 본다
        var tools = new FakeTools(Map.of("show_access_code", new ToolGateway.Outcome(
                "{\"reservationId\":7,\"owner\":true,\"cardShown\":true,\"accessCode\":\"482913\",\"message\":\"카드로 보여 줬음\"}",
                false)));

        ChatResult result = loop(llm, tools).run(ME, List.of(), "입실 번호 알려 줘");

        assertThat(result.accessCodeCards()).containsExactly(new ChatResult.AccessCodeCard(7));
        assertThat(result.confirmations()).isEmpty();
        assertThat(llm.seen.get(1).getLast()).isInstanceOfSatisfying(LlmMessage.ToolResult.class,
                r -> assertThat(r.json()).contains("\"cardShown\":true").doesNotContain("482913", "accessCode"));
    }

    @Test
    @DisplayName("시나리오 '입실 번호 알려 줘'(참여자): 카드 없이 대표자에게 문의하라는 안내만")
    void participantAccessCodeHasNoCard() {
        var llm = new ScriptedLlm().then(tool("show_access_code", Map.of("reservationId", 9)))
                .then(say("대표자가 아니라 입실 번호를 알 수 없어요. 대표자에게 문의하세요."));
        var tools = new FakeTools(Map.of("show_access_code", new ToolGateway.Outcome(
                "{\"reservationId\":9,\"owner\":false,\"cardShown\":false,\"message\":\"대표자에게 문의하세요.\"}",
                false)));

        ChatResult result = loop(llm, tools).run(ME, List.of(), "입실 번호 뭐야?");

        assertThat(result.accessCodeCards()).isEmpty();
        assertThat(result.reply()).contains("대표자에게 문의하세요");
    }

    @Test
    @DisplayName("지시문에 입실 번호를 지어내지 말라는 규칙이 있다")
    void systemPromptForbidsInventingAccessCode() {
        var llm = new ScriptedLlm().then(say("네"));

        loop(llm, new FakeTools(Map.of())).run(ME, List.of(), "안녕");

        assertThat(llm.lastSystem).contains("show_access_code", "번호를 지어내지 않는다");
    }

    @Test
    @DisplayName("학생이 채팅에 적은 6자리 입실 번호도 외부 AI로 보내기 전에 가린다")
    void masksAccessCode() {
        // given
        var llm = new ScriptedLlm().then(say("화면 카드에서 확인해 주세요"));

        // when
        loop(llm, new FakeTools(Map.of())).run(ME, List.of(), "입실 번호 482913 맞아? 1번 방 14:00 예약");

        // then
        String sent = llm.seen.getFirst().toString();
        assertThat(sent).doesNotContain("482913").contains("[입실 번호]", "1번 방 14:00");
    }

    @Test
    @DisplayName("도구 결과와 모델 답도 다음 요청 전에 가린다: 어떤 도구의 accessCode든 지우고 학번·전화번호는 가린다")
    void masksToolResultsAndModelReplies() {
        // given: 도구가 (원래는 없지만) 목록 안에 입실 번호와 학번·전화번호를 실어 보낸다
        String leaky = """
                {"reservations":[{"id":7,"accessCode":"482913","note":"참여자 20230002, 010-1234-5678"}]}""";
        var llm = new ScriptedLlm()
                .then(new LlmReply("학번 20230001 확인할게요", List.of(new ToolCall("c1", "my_reservations", Map.of()))))
                .then(say("내 예약은 1건이에요"));
        var tools = new FakeTools(Map.of("my_reservations", new ToolGateway.Outcome(leaky, false)));

        // when
        loop(llm, tools).run(ME, List.of(), "내 예약 보여 줘");

        // then
        String sent = llm.seen.get(1).toString();
        assertThat(sent).doesNotContain("482913", "20230002", "010-1234-5678", "20230001", "accessCode")
                .contains("\"id\":7", "[학번]", "[전화번호]");
    }

    @Test
    @DisplayName("도구 결과의 6자리 숫자 값(예약 id 123456)은 그대로 둬서 JSON이 깨지지 않고, 문자열 안의 6자리만 가린다")
    void keepsNumericIdsInToolResults() throws Exception {
        // given
        String result = """
                {"reservations":[{"id":123456,"roomId":2,"note":"번호 482913 맞아요"}]}""";
        var llm = new ScriptedLlm()
                .then(tool("my_reservations", Map.of()))
                .then(say("내 예약은 1건이에요"));
        var tools = new FakeTools(Map.of("my_reservations", new ToolGateway.Outcome(result, false)));

        // when
        loop(llm, tools).run(ME, List.of(), "내 예약 보여 줘");

        // then
        var toolResult = (LlmMessage.ToolResult) llm.seen.get(1).get(2);
        var parsed = JSON.readTree(toolResult.json());
        assertThat(parsed.get("reservations").get(0).get("id").asLong()).isEqualTo(123456L);
        assertThat(toolResult.json()).doesNotContain("482913").contains("[입실 번호]");
    }

    @Test
    @DisplayName("오류 결과도 외부 AI로 보내기 전에 가린다 (JSON 아닌 문장 포함)")
    void masksErrorResults() {
        // given
        var llm = new ScriptedLlm()
                .then(tool("cancel_reservation", Map.of("reservationId", 7)))
                .then(say("다시 해 볼게요"));
        var tools = new FakeTools(Map.of("cancel_reservation",
                new ToolGateway.Outcome("학번 20230002 학생의 예약이 아니에요", true)));

        // when
        loop(llm, tools).run(ME, List.of(), "취소해 줘");

        // then
        assertThat(llm.seen.get(1).toString()).doesNotContain("20230002").contains("[학번]");
    }

    @Test
    @DisplayName("오류 결과에 확인 토큰이 실려 와도 모델에 넘기지 않고 카드도 만들지 않는다")
    void dropsConfirmTokenFromErrorResults() {
        // given
        var llm = new ScriptedLlm()
                .then(tool("create_reservation", Map.of("roomId", 2)))
                .then(say("다시 해 볼게요"));
        var tools = new FakeTools(Map.of("create_reservation", new ToolGateway.Outcome(
                "{\"code\":\"RESERVATION_TIME_TAKEN\",\"confirmToken\":\"c_leak\"}", true)));

        // when
        ChatResult result = loop(llm, tools).run(ME, List.of(), "내일 2시 2번 방 잡아 줘");

        // then
        assertThat(result.confirmations()).isEmpty();
        assertThat(llm.seen.get(1).toString()).doesNotContain("c_leak").contains("RESERVATION_TIME_TAKEN");
    }

    @Test
    @DisplayName("모델이 지어낸 도구 이름은 로그에 그대로 남기지 않는다 (학생 말이 섞일 수 있음)")
    void logsOnlyKnownToolNames() {
        // given
        var tools = new FakeTools(Map.of("find_available_rooms", new ToolGateway.Outcome(FREE, false)));

        // when
        try (ToolGateway.Session session = tools.open(ME)) {
            // then
            assertThat(AgentLoop.loggable("find_available_rooms", session)).isEqualTo("find_available_rooms");
            assertThat(AgentLoop.loggable("홍길동 학생 예약 취소", session)).isEqualTo("unknown");
        }
    }

    @Test
    @DisplayName("카드를 만든 뒤 모델 답이 실패해도 카드는 화면에 돌려준다")
    void keepsCardsWhenLlmFailsAfterTool() {
        // given: 도구 결과 뒤 모델이 시간 초과
        LlmClient llm = new LlmClient() {
            int n;

            @Override
            public LlmReply chat(String system, List<LlmMessage> conversation, List<ToolSpec> tools) {
                if (n++ == 0) {
                    return tool("show_access_code", Map.of());
                }
                throw new LlmException("LLM_UNAVAILABLE", "응답 없음");
            }
        };
        var tools = new FakeTools(Map.of("show_access_code", new ToolGateway.Outcome(
                "{\"reservationId\":7,\"owner\":true,\"cardShown\":true,\"message\":\"카드로 보여 줬음\"}", false)));

        // when
        ChatResult result = loop(llm, tools).run(ME, List.of(), "입실 번호 알려 줘");

        // then
        assertThat(result.accessCodeCards()).containsExactly(new ChatResult.AccessCodeCard(7));
        assertThat(result.reply()).contains("카드는 그대로 쓸 수 있어요");
    }

    @Test
    @DisplayName("카드가 없을 때 모델이 실패하면 오류를 그대로 올린다")
    void rethrowsWhenNoCards() {
        // given
        LlmClient llm = (system, conversation, tools) -> {
            throw new LlmException("LLM_UNAVAILABLE", "응답 없음");
        };

        // when, then
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> loop(llm, new FakeTools(Map.of())).run(ME, List.of(), "안녕"))
                .isInstanceOf(LlmException.class);
    }
}
