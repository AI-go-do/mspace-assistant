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
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentLoop;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ToolGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmClient;
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
                    return results.getOrDefault(name, new Outcome("{\"message\":\"없는 도구\"}", true));
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

    static AgentLoop loop(LlmClient llm, ToolGateway tools) {
        return new AgentLoop(llm, tools, new AgentProperties(null, null, 8, null), JsonMapper.builder().build(), CLOCK);
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

        assertThat(result.reply()).isEqualTo("2번 방이 비어 있어요");
        assertThat(tools.called).containsExactly("find_available_rooms");
        assertThat(llm.seen.get(1).getLast()).isInstanceOfSatisfying(LlmMessage.ToolResult.class,
                r -> assertThat(r.json()).contains("\"roomId\":2"));
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
        assertThat(result.toolCalls()).isEqualTo(8);
        assertThat(tools.called).hasSize(8);
        assertThat(result.reply()).contains("너무 많아져서 멈췄어요");
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
    @DisplayName("외부 AI로 보내기 전에 학번·전화번호(+82 포함)를 가린다. 지난 대화의 비서 차례도")
    void masksPersonalInfo() {
        var llm = new ScriptedLlm().then(say("알겠어요"));

        loop(llm, new FakeTools(Map.of())).run(ME, List.of(new LlmMessage.User("내 번호는 010-1234-5678"),
                        new LlmMessage.Assistant("+82 10-9876-5432로 연락드릴게요", List.of())),
                "친구 학번 20231234도 넣어 줘");

        String sent = llm.seen.getFirst().toString();
        assertThat(sent).doesNotContain("20231234", "010-1234-5678", "9876-5432").contains("[학번]", "[전화번호]");
    }

    @Test
    @DisplayName("시스템 지시문에 오늘 날짜(서울)를 넣어 '내일'을 날짜로 바꿀 수 있게 한다")
    void systemPromptHasToday() {
        var llm = new ScriptedLlm().then(say("네"));

        loop(llm, new FakeTools(Map.of())).run(ME, List.of(), "내일 빈방");

        assertThat(llm.lastSystem).contains("2026-11-02(월) 15:00").contains("[확인]");
    }

    @Test
    @DisplayName("시나리오 '수업 끝나고 2시간 잡아 줘': 시간표 도구가 없으니 모델은 끝나는 시각을 되묻는다(지시문 규칙)")
    void asksClassEndTime() {
        var llm = new ScriptedLlm().then(say("수업이 몇 시에 끝나요?"));
        var tools = new FakeTools(Map.of("find_available_rooms", new ToolGateway.Outcome(FREE, false)));

        ChatResult result = loop(llm, tools).run(ME, List.of(), "수업 끝나고 2시간 잡아 줘");

        assertThat(llm.lastSystem).contains("수업 시간표는 아직 모른다");
        assertThat(result.reply()).contains("몇 시에 끝나요");
        assertThat(tools.called).isEmpty();
    }
}
