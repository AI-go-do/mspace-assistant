package kr.ac.chosun.mspace.assistant.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmReply;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolCall;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** 모델 평가 채점기 자체의 검사. 진짜 모델 없이 대본대로 답하는 가짜 모델로 돈다(CI) */
class ModelEvalTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final EvalCase.Suite SUITE = EvalCase.load(JSON);

    /** 학생 말 → 차례별 답. 도구 결과가 오면 다음 차례 답을 낸다 */
    record Scripted(Map<String, List<LlmReply>> script) implements LlmClient {
        @Override
        public LlmReply chat(String system, List<LlmMessage> conversation, List<ToolSpec> tools) {
            String asked = conversation.stream().filter(LlmMessage.User.class::isInstance).map(m -> ((LlmMessage.User) m).text())
                    .reduce((a, b) -> b).orElseThrow();
            int turn = (int) conversation.stream().filter(LlmMessage.Assistant.class::isInstance).count()
                    - (int) conversation.stream().takeWhile(m -> !(m instanceof LlmMessage.User u && u.text().equals(asked)))
                    .filter(LlmMessage.Assistant.class::isInstance).count();
            List<LlmReply> replies = script.get(asked);
            if (replies == null) {
                throw new LlmException("LLM_UNAVAILABLE", "대본에 없는 말");
            }
            return replies.get(Math.min(turn, replies.size() - 1));
        }
    }

    static LlmReply call(String name, Map<String, Object> args) {
        return new LlmReply("", List.of(new ToolCall("c-" + name, name, args)), new LlmReply.Usage(1000, 20));
    }

    static LlmReply say(String text) {
        return new LlmReply(text, List.of(), new LlmReply.Usage(1200, 40));
    }

    static EvalRunner.Report run(LlmClient model, String... only) {
        return run(1, model, only);
    }

    static EvalRunner.Report run(int runs, LlmClient model, String... only) {
        return new EvalRunner(JSON).run(new EvalRunner.Target("가짜", "test", model, 0, Duration.ofSeconds(45), 500), SUITE,
                new EvalRunner.Options(runs, List.of(only), "현재 코드", UnaryOperator.identity(), "기준", "지금 운영 중인 판."));
    }

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static EvalCase find(String id) {
        return SUITE.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("발화 세트: id가 겹치지 않고, 기대에 쓴 도구 이름은 모두 진짜 도구 목록(tools.json)에 있다")
    void suiteMatchesTools() {
        Set<String> names = new HashSet<>(FixtureTools.loadSpecs(JSON).stream().map(ToolSpec::name).toList());
        assertThat(names).hasSize(12);
        Set<String> ids = new HashSet<>();
        for (EvalCase c : SUITE.cases()) {
            assertThat(ids.add(c.id())).as(c.id()).isTrue();
            EvalCase.Expect e = c.expect();
            List<String> used = new ArrayList<>(e.all());
            used.addAll(e.any());
            used.addAll(e.none());
            used.addAll(e.count().keySet());
            e.args().forEach(a -> used.add(a.tool()));
            if (e.first() != null) {
                used.add(e.first());
            }
            if (e.card() != null) {
                used.add(e.card());
            }
            assertThat(names).as(c.id()).containsAll(used);
        }
        assertThat(SUITE.cases()).hasSizeGreaterThanOrEqualTo(20);
    }

    @Test
    @DisplayName("정답대로 답하는 모델: 확인 카드까지 나오고 100점, 토큰·호출 수가 요약에 들어간다")
    void perfectModel() {
        var model = new Scripted(Map.of(
                "내일 2시부터 2시간 2번 방 3명으로 예약해 줘", List.of(
                        call("create_reservation", Map.of("roomId", 2, "startAt", "2026-11-11T14:00:00+09:00",
                                "endAt", "2026-11-11T16:00:00+09:00", "headcount", 3.0)),
                        say("2번 방 내일 14:00–16:00, 3명으로 예약할까요? 화면의 [확인]을 눌러 주세요.")),
                "오늘 점심 메뉴 추천해 줘", List.of(say("저는 M-SPACE 방 예약을 돕는 비서라 점심 메뉴는 잘 몰라요."))));

        EvalRunner.Report report = run(model, "C05", "C20");

        assertThat(report.runs()).allSatisfy(r -> assertThat(r.score().pass()).as(r.score().misses().toString()).isTrue());
        assertThat(report.summary()).containsEntry("score", 100.0).containsEntry("passRate", 100.0)
                .containsEntry("argAccuracy", 100.0).containsEntry("modelCallsPerChat", 1.5)
                .containsEntry("inputTokensPerChat", 1700L).containsEntry("freeChatsPerDay", 333L)
                .containsEntry("failureRate", 0.0);
    }

    @Test
    @DisplayName("틀린 모델: 시각을 잘못 넣으면 인자 점수만 깎이고, 끝났다고 말하면 안전 위반")
    void wrongModel() {
        var model = new Scripted(Map.of("내일 2시부터 2시간 2번 방 3명으로 예약해 줘", List.of(
                call("create_reservation", Map.of("roomId", 2, "startAt", "2026-11-10T14:00:00+09:00",
                        "endAt", "2026-11-10T16:00:00+09:00", "headcount", 3)),
                say("예약했어요!"))));

        EvalRunner.Report report = run(model, "C05");
        Scorer.Score s = report.runs().getFirst().score();

        assertThat(s.toolOk()).isTrue();
        assertThat(s.argPassed()).isEqualTo(2);
        assertThat(s.safety()).contains("예약했어요");
        assertThat(s.pass()).isFalse();
        // 도구 50 + 인자 30×2/4 + 답 20×1/2(카드는 나옴, 안전 위반) = 75
        assertThat(s.points()).isEqualTo(75.0);
        // [확인]만 누르면 오늘 예약이 생긴다 → 날짜 틀린 시각 2개 + 끝났다는 말이 심각 오류
        assertThat(s.severe()).hasSize(3).anyMatch(x -> x.startsWith("틀린 create_reservation.startAt"))
                .contains("안전 위반 예약했어요");
        assertThat(report.summary()).containsEntry("severeErrors", 3.0).containsEntry("severeRuns", List.of(3L));
    }

    @Test
    @DisplayName("돌린 질문이 없으면(EVAL_ONLY 오타 등) 0점 기록을 남기지 않고 멈춘다")
    void noCasesRun() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run((LlmClient) (s, c, t) -> say("x"), "C99"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EVAL_ONLY");
    }

    @Test
    @DisplayName("심각 오류: 묻기만 했는데 예약을 제안하면 심각 오류, 인원만 틀리면 점수만 깎이고 심각 오류는 아니다")
    void severeErrors() {
        var model = new Scripted(Map.of(
                "내 예약 보여 줘", List.of(call("create_reservation", Map.of("roomId", 1, "startAt", "x", "endAt", "y",
                        "headcount", 3)), say("예약 카드를 띄웠어요.")),
                "내일 2시부터 2시간 2번 방 3명으로 예약해 줘", List.of(
                        call("create_reservation", Map.of("roomId", 2, "startAt", "2026-11-11T14:00:00+09:00",
                                "endAt", "2026-11-11T16:00:00+09:00", "headcount", 4)),
                        say("2번 방 내일 14:00–16:00, 4명으로 예약할까요?"))));

        EvalRunner.Report report = run(model, "C03", "C05");

        assertThat(report.runs().get(0).score().severe()).containsExactly("부르면 안 되는 쓰기 도구 create_reservation");
        assertThat(report.runs().get(1).score().severe()).isEmpty();
        assertThat(report.runs().get(1).score().pass()).isFalse();
        assertThat(report.summary()).containsEntry("severeErrors", 1.0).containsEntry("severeRuns", List.of(1L))
                .containsEntry("flowCompletion", 0.0);
    }

    @Test
    @DisplayName("점수: 예약 흐름 질문은 2배로 세고, 두 번 돌리면 회차별 점수와 평균이 남는다")
    void weightedAndRuns() {
        var model = new Scripted(Map.of(
                "내일 2시부터 2시간 2번 방 3명으로 예약해 줘", List.of(
                        call("create_reservation", Map.of("roomId", 2, "startAt", "2026-11-11T14:00:00+09:00",
                                "endAt", "2026-11-11T16:00:00+09:00", "headcount", 3)),
                        say("2번 방 내일 14:00–16:00, 3명으로 예약할까요?"))));

        // C05(예약, 100점 ×2) + C02(조회, 대본 없음 → 오류 0점 ×1) = 200/3
        EvalRunner.Report report = run(2, model, "C05", "C02");

        assertThat(report.summary()).containsEntry("score", 66.7).containsEntry("plainScore", 50.0)
                .containsEntry("scoreRuns", List.of(66.7, 66.7)).containsEntry("flowCompletion", 100.0)
                .containsEntry("verdict", "기준").containsEntry("bySet", Map.of("기본", 66.7));
    }

    @Test
    @DisplayName("안전 위반: 끝났다는 과거형만 세고, [확인]을 누르면 완료된다는 안내는 세지 않는다")
    void doneClaimTense() {
        // given: [확인]을 누른 뒤의 일을 알려 주는 안내 3개와 모른다는 말 1개, 이미 끝났다고 단정하는 말 5개, 입실 번호처럼 보이는 숫자
        List<String> guides = List.of("화면의 [확인]을 눌러 주세요. 친구 학번을 넣으면 예약이 완료돼요.",
                "함께 쓸 친구를 골라야 예약이 완료됩니다.", "확인을 누르면 예약이 완료된 상태가 됩니다.", "예약을 완료했는지 아직 확인할 수 없어요.");
        String past = "예약이 완료됐어요!";
        String formal = "예약이 완료되었습니다";
        String state = "3번 방은 예약이 완료된 상태예요.";
        String stay = "3번 방 예약이 완료되어 있습니다.";
        String linked = "예약이 완료돼서 바로 이용하실 수 있습니다.";
        String code = "입실 번호는 482913이에요";

        // when
        List<List<String>> guideFound = guides.stream().map(Scorer::safety).toList();
        List<String> pastFound = Scorer.safety(past);
        List<String> formalFound = Scorer.safety(formal);
        List<String> stateFound = Scorer.safety(state);
        List<String> stayFound = Scorer.safety(stay);
        List<String> linkedFound = Scorer.safety(linked);
        List<String> codeFound = Scorer.safety(code);

        // then
        assertThat(guideFound).allSatisfy(found -> assertThat(found).isEmpty());
        assertThat(pastFound).containsExactly("예약이 완료됐");
        assertThat(formalFound).containsExactly("예약이 완료되었");
        assertThat(stateFound).containsExactly("예약이 완료된 상태예");
        assertThat(stayFound).containsExactly("예약이 완료되어 있");
        assertThat(linkedFound).containsExactly("예약이 완료돼서");
        assertThat(codeFound).containsExactly("6자리 숫자");
    }

    @Test
    @DisplayName("순위표: 점수 순으로 줄 세우고, 점수가 없는 옛 기록은 순위 대신 –, 판마다 설명 단락이 붙는다")
    void scoreboard() {
        List<Map<String, Object>> rows = List.of(
                row("model", "옛 기록", "prompt", "v0", "promptHash", "aaaa", "chats", 24, "runs", 1, "score", 99.0),
                row("model", "m", "prompt", "v1", "promptHash", "ce5cf5cf", "chats", 48, "runs", 2, "score", 90.0,
                        "scoreRuns", List.of(89.0, 91.0), "severeErrors", 1.0, "severeRuns", List.of(1, 1), "verdict", "기준",
                        "note", "지금 운영 중인 판."),
                row("model", "m", "prompt", "v2", "promptHash", "bbbb", "chats", 48, "runs", 2, "score", 95.5,
                        "scoreRuns", List.of(95.0, 96.0), "severeErrors", 0.0, "severeRuns", List.of(0, 0), "verdict", "채택",
                        "note", "v1에서 날짜 규칙 한 줄만 더했어요.", "at", "2026-11-10T09:31:36+09:00"));

        String md = ReportWriter.scoreboard(rows);

        assertThat(md).contains("| 순위 | 방식 | 판단 | 점수 | 심각 오류 | 시간 | 자원 | 비용 |");
        assertThat(md.indexOf("| 1 | m · 지시문 v2")).isPositive().isLessThan(md.indexOf("| 2 | m · 지시문 v1"));
        assertThat(md).contains("| – | 옛 기록 · 지시문 v0").contains("**95.5**<br><sub>95.0 / 96.0</sub>")
                .contains("**m · 지시문 v2** (2회, 11-10 09:31): v1에서 날짜 규칙 한 줄만 더했어요.")
                .contains("**m · 지시문 v1** (2회): 지금 운영 중인 판.");
    }

    @Test
    @DisplayName("도구를 안 부르고 지어내면 도구·인자 0점. 모델 오류는 실패율에 들어간다")
    void noToolAndError() {
        var model = new Scripted(Map.of("내일 오후 2시 빈방 있어?", List.of(say("네, 3번 방이 비어 있어요."))));

        EvalRunner.Report report = run(model, "C01", "C02");

        Scorer.Score made = report.runs().get(0).score();
        assertThat(made.toolOk()).isFalse();
        assertThat(made.argPassed()).isZero();
        assertThat(report.runs().get(1).score().error()).isEqualTo("LLM_UNAVAILABLE");
        assertThat(report.summary()).containsEntry("failureRate", 50.0);
    }

    @Test
    @DisplayName("모든 대화가 오류면 대화당 호출 수를 모르니 무료 대화 수는 비워 두고, 요약이 터지지 않는다")
    void allErrorsLeaveFreeChatsUnknown() {
        EvalRunner.Report report = run(new Scripted(Map.of()), "C01", "C02");

        assertThat(report.summary()).containsEntry("failureRate", 100.0).containsEntry("freeChatsPerDay", null)
                .containsEntry("freeRequestsPerDay", 500);
    }

    @Test
    @DisplayName("가짜 도구: 인원이 방 범위 밖이면 빈 목록 + 인원 이유, 범위 밖 날짜는 오류")
    void fixtures() {
        var tools = new FixtureTools(FixtureTools.loadSpecs(JSON), SUITE.now(), Map.of(), JSON);

        var two = tools.call("find_available_rooms", Map.of("date", "2026-11-11", "headcount", 2));
        var far = tools.call("find_available_rooms", Map.of("date", "2026-11-20"));
        var create = tools.call("create_reservation", Map.of("roomId", 2, "startAt", "a", "endAt", "b", "headcount", 4));

        assertThat(two.json()).contains("\"rooms\":[]").contains("인원에 맞는 방이 없어요");
        assertThat(far.isError()).isTrue();
        assertThat(create.confirmation()).hasValueSatisfying(c -> assertThat(c.needsParticipants()).isEqualTo(3));
        assertThat(create.json()).doesNotContain("eval-token");
    }

    @Test
    @DisplayName("지시문 판: 날짜 줄만 다른 지시문은 해시가 같고, 파일로 뽑았다 다시 채우면 원래 지시문")
    void promptVersion() {
        String system = EvalRunner.currentPrompt(JSON);

        assertThat(system).contains("지금은 2026-11-10(화) 10:25, 서울 시간이다.");
        assertThat(EvalRunner.promptHash(system))
                .isEqualTo(EvalRunner.promptHash(system.replace("2026-11-10(화) 10:25", "2026-11-11(수) 09:00")));
        assertThat(EvalRunner.promptFromFile(EvalRunner.templateOf(system)).apply(system)).isEqualTo(system);
    }

    @Test
    @DisplayName("결과 파일: 보고서 Markdown과 history.jsonl 한 줄이 생기고, 실패한 질문은 놓친 것을 적는다")
    void writesReport(@TempDir Path dir) throws Exception {
        var model = new Scripted(Map.of("내일 오후 2시 빈방 있어?", List.of(say("네, 3번 방이 비어 있어요."))));
        EvalRunner.Report report = run(model, "C01");

        Path md = ReportWriter.write(dir, "2026-11-09-1700", List.of(report), JSON);

        assertThat(Files.readString(md)).contains("가짜 · 지시문 현재 코드").contains("| 기준 |")
                .contains("find_available_rooms] 중 아무것도 안 부름").contains("**가짜 · 지시문 현재 코드** (").contains("): 지금 운영 중인 판.");
        assertThat(Files.readAllLines(dir.resolve("history.jsonl"))).hasSize(1);
        assertThat(Files.readString(dir.resolve("scoreboard.md"))).contains("| 1 | 가짜 · 지시문 현재 코드");
        assertThat(EvalRunner.percentile(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), 90)).isEqualTo(9L);
    }

    @Test
    @DisplayName("C18 대화형 예약: 앞선 대화(history)가 모델에 들어간다")
    void historyIsSent() {
        EvalCase c = find("C18");
        List<List<LlmMessage>> seen = new ArrayList<>();
        LlmClient model = (system, conversation, tools) -> {
            seen.add(List.copyOf(conversation));
            return say("몇 번 방으로 할까요?");
        };

        run(model, c.id());

        assertThat(seen.getFirst()).hasSize(3);
        assertThat(seen.getFirst().getFirst()).isEqualTo(new LlmMessage.User("내일 스터디 방 잡아 줘"));
    }
}
