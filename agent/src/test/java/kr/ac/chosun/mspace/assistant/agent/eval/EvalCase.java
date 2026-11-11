package kr.ac.chosun.mspace.assistant.agent.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import tools.jackson.databind.json.JsonMapper;

/**
 * 고정 발화 한 개와 기대. 파일은 src/test/resources/eval/cases.json.
 *
 * @param history  앞선 대화(대화형 예약처럼 여러 차례에 걸친 것). 학생·비서 글만
 * @param fixtures 이 질문에서만 바꿀 도구 결과 (예: my_reservations에 사용 중 예약 하나)
 * @param set      발화 세트. 없으면 "기본"(C01–C24). 새 세트(예: 여러 차례 대화 "대화")는 점수를 따로 내서 이전 판과 비교가 깨지지 않게 한다
 */
record EvalCase(String id, String group, String text, List<LlmMessage> history, Map<String, Object> fixtures,
                Expect expect, String set) {

    static final String DEFAULT_SET = "기본";

    /** 대화로 예약이 끝까지 되는 기본 흐름(11/10 영준: 가장 중요). 점수에서 2배로 센다 */
    static final Set<String> FLOW_GROUPS = Set.of("예약", "즉시 시작", "연장", "취소·퇴실", "대화형",
            "지난번처럼", "시간표");
    static final int FLOW_WEIGHT = 2;

    boolean flow() {
        return FLOW_GROUPS.contains(group);
    }

    int weight() {
        return flow() ? FLOW_WEIGHT : 1;
    }

    /**
     * 기대. 비어 있는 항목은 보지 않는다.
     *
     * @param first     첫 도구
     * @param all       모두 불러야 할 도구
     * @param any       이 중 하나는 불러야 함
     * @param none      부르면 안 되는 도구
     * @param noTool    도구를 하나도 부르지 않아야 함
     * @param count     도구별 정확한 호출 횟수
     * @param args      인자 검사. 같은 도구를 여러 번 불렀으면 마지막 호출을 본다(최종 판단)
     * @param card      나와야 할 확인 카드의 도구
     * @param replyAny  답에 이 말 중 하나
     * @param replyNone 답에 있으면 안 되는 말
     * @param asks      답이 사용자에게 되묻는 말이어야 함(물음표나 "알려 주세요"). replyAny와 함께 한 검사로 본다
     */
    record Expect(String first, List<String> all, List<String> any, List<String> none, boolean noTool,
                  Map<String, Integer> count, List<ArgCheck> args, String card, List<String> replyAny,
                  List<String> replyNone, boolean asks) {

        boolean checksTools() {
            return first != null || !all.isEmpty() || !any.isEmpty() || !none.isEmpty() || noTool || !count.isEmpty();
        }
    }

    /**
     * 인자 하나. equals(값이 같음), prefix(앞부분이 같음: 시각의 초·시간대 표기 차이를 넘긴다. 여러 개면 그중 하나), oneOf(이 중 하나),
     * absent(넣지 않아야 함) 중 하나
     */
    record ArgCheck(String tool, String arg, Object equals, List<String> prefix, List<Object> oneOf, boolean absent) {

        String describe() {
            String want = absent ? "없음" : equals != null ? String.valueOf(equals)
                    : prefix != null ? String.join("|", prefix) + "…" : String.valueOf(oneOf);
            return tool + "." + arg + "=" + want;
        }
    }

    record Suite(OffsetDateTime now, List<EvalCase> cases) {
    }

    @SuppressWarnings("unchecked")
    static Suite load(JsonMapper json) {
        try (InputStream in = EvalCase.class.getResourceAsStream("/eval/cases.json")) {
            Map<String, Object> root = json.readValue(in, Map.class);
            List<EvalCase> cases = new ArrayList<>();
            for (Map<String, Object> c : (List<Map<String, Object>>) root.get("cases")) {
                cases.add(parse(c));
            }
            return new Suite(OffsetDateTime.parse((String) root.get("now")), List.copyOf(cases));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static EvalCase parse(Map<String, Object> c) {
        List<LlmMessage> history = new ArrayList<>();
        for (Map<String, String> h : (List<Map<String, String>>) c.getOrDefault("history", List.of())) {
            history.add("user".equals(h.get("role")) ? new LlmMessage.User(h.get("text"))
                    : new LlmMessage.Assistant(h.get("text"), List.of()));
        }
        Map<String, Object> e = (Map<String, Object>) c.getOrDefault("expect", Map.of());
        List<ArgCheck> args = new ArrayList<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) e.getOrDefault("args", List.of())) {
            args.add(new ArgCheck((String) a.get("tool"), (String) a.get("arg"), a.get("equals"), prefixes(a.get("prefix")),
                    (List<Object>) a.get("oneOf"), Boolean.TRUE.equals(a.get("absent"))));
        }
        Expect expect = new Expect((String) e.get("first"), strings(e.get("all")), strings(e.get("any")),
                strings(e.get("none")), Boolean.TRUE.equals(e.get("noTool")),
                (Map<String, Integer>) e.getOrDefault("count", Map.of()), List.copyOf(args), (String) e.get("card"),
                strings(e.get("replyAny")), strings(e.get("replyNone")), Boolean.TRUE.equals(e.get("asks")));
        return new EvalCase((String) c.get("id"), (String) c.get("group"), (String) c.get("text"), List.copyOf(history),
                (Map<String, Object>) c.getOrDefault("fixtures", Map.of()), expect,
                (String) c.getOrDefault("set", DEFAULT_SET));
    }

    @SuppressWarnings("unchecked")
    private static List<String> prefixes(Object v) {
        return v == null ? null : v instanceof String one ? List.of(one) : List.copyOf((List<String>) v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object v) {
        return v == null ? List.of() : List.copyOf((List<String>) v);
    }
}
