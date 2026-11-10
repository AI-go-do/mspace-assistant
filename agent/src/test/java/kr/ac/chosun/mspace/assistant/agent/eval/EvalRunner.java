package kr.ac.chosun.mspace.assistant.agent.eval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentLoop;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.MemoryProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.PersonaStore;
import kr.ac.chosun.mspace.assistant.agent.chat.application.PrivacyMask;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모델 평가 실행기. 진짜 AgentLoop(지시문·반복·최대 횟수 그대로)에 모델만 바꿔 끼우고, 도구는 가짜(FixtureTools)로 둔다.
 * 질문마다 새 대화로 돌리고 Scorer로 채점해 모델·지시문 판마다 한 줄 요약을 만든다.
 */
final class EvalRunner {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final UserHeaders EVAL_USER = new UserHeaders(Map.of("X-User-Id", "eval"));
    private static final Pattern NOW_LINE = Pattern.compile("지금은 (.+?), 서울 시간이다\\.");

    /**
     * @param label         결과 표에 쓸 이름 (예: gemini-3.5-flash-lite)
     * @param minIntervalMs 호출 사이 최소 간격. 무료 분당 한도(RPM)를 지킨다
     * @param chatTimeout   대화 한 번 상한. 서버와 같은 45초가 기본. 젯슨처럼 느린 곳은 늘려서 재고 표에 적는다
     * @param freeRpd       무료 하루 요청 수. 모르면 null, 로컬이면 -1(제한 없음)
     */
    record Target(String label, String provider, LlmClient client, long minIntervalMs, Duration chatTimeout,
                  Integer freeRpd) {
    }

    /**
     * @param prompt  지시문 바꾸기. 그대로면 identity
     * @param verdict 표의 판단: 기준·채택·비교·탈락. 지금 운영 중인 판이 기준
     * @param note    표 아래 한 단락: 이전 판에서 무엇을 바꿨고 왜 점수가 변했나 (판마다 한 가지만 바꾼다)
     */
    record Options(int runs, List<String> only, String promptLabel, UnaryOperator<String> prompt, String verdict,
                   String note) {

        Options(int runs, List<String> only, String promptLabel, UnaryOperator<String> prompt) {
            this(runs, only, promptLabel, prompt, "비교", "");
        }
    }

    record CaseRun(int run, EvalCase evalCase, Scorer.Score score, List<RecordingLlm.Call> calls, List<String> tools,
                   String snippet) {

        long modelMillis() {
            return calls.stream().mapToLong(RecordingLlm.Call::millis).sum();
        }
    }

    record Report(Map<String, Object> summary, List<CaseRun> runs) {
    }

    private final JsonMapper json;
    private final List<ToolSpec> specs;

    EvalRunner(JsonMapper json) {
        this.json = json;
        this.specs = FixtureTools.loadSpecs(json);
    }

    Report run(Target target, EvalCase.Suite suite, Options options) {
        RecordingLlm llm = new RecordingLlm(target.client(), target.minIntervalMs(), options.prompt());
        AgentProperties props = new AgentProperties(null, null, 8, Duration.ofSeconds(10),
                new AgentProperties.Rules(30, 2, 7), target.chatTimeout(), 1000, 20);
        List<CaseRun> runs = new ArrayList<>();
        for (int run = 1; run <= options.runs(); run++) {
            for (EvalCase c : suite.cases()) {
                if (!options.only().isEmpty() && !options.only().contains(c.id())) {
                    continue;
                }
                // 시계는 고정 시각에서 출발해 실제 시간만큼 흐른다 (지시문의 날짜는 늘 같고, 45초 상한은 진짜로 걸린다)
                Clock clock = Clock.offset(Clock.system(SEOUL), Duration.between(Instant.now(), suite.now().toInstant()));
                AgentLoop loop = new AgentLoop(llm, new FixtureTools(specs, suite.now(), c.fixtures(), json), props, clock,
                        ConversationMemory.NONE, new MemoryProperties(false, null, null, 0), null, PersonaStore.NONE);
                ChatResult result = null;
                String error = null;
                try {
                    result = loop.run(EVAL_USER, c.history(), c.text());
                } catch (LlmException e) {
                    error = e.code();
                } catch (RuntimeException e) {
                    error = e.getClass().getSimpleName();
                }
                List<RecordingLlm.Call> calls = llm.drain();
                Scorer.Score score = Scorer.score(c, result, calls, error);
                String snippet = result == null ? "" : PrivacyMask.mask(result.reply()).replace("\n", " ");
                runs.add(new CaseRun(run, c, score, calls, result == null ? List.of() : result.tools(),
                        snippet.length() > 120 ? snippet.substring(0, 120) + "…" : snippet));
                System.out.printf("[%s %d/%d] %s %s %.0f점 %s%n", target.label(), run, options.runs(), c.id(),
                        score.pass() ? "통과" : "실패", score.points(), String.join(" / ", score.misses()));
            }
        }
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("돌린 질문이 없어요. EVAL_RUNS와 EVAL_ONLY를 확인해 주세요");
        }
        return new Report(summarize(target, options, promptHash(llm.lastSystem()), runs), runs);
    }

    /** 모델 없이 지금 코드의 지시문(고정 시각)을 얻는다. 모델 자리에 지시문만 받아 두는 가짜를 끼워 한 번 돌린다 */
    static String currentPrompt(JsonMapper json) {
        EvalCase.Suite suite = EvalCase.load(json);
        String[] seen = new String[1];
        LlmClient capture = (system, conversation, tools) -> {
            seen[0] = system;
            return new kr.ac.chosun.mspace.assistant.agent.llm.application.LlmReply("지시문만 받았어요", List.of());
        };
        EvalRunner runner = new EvalRunner(json);
        runner.run(new Target("prompt", "none", capture, 0, Duration.ofSeconds(45), null), suite,
                new Options(1, List.of(suite.cases().getFirst().id()), "현재 코드", UnaryOperator.identity()));
        return seen[0];
    }

    /** 판 구분용 지시문 해시. 날짜 줄은 빼고 잰다(날짜만 다르면 같은 판) */
    static String promptHash(String system) {
        if (system == null) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(NOW_LINE.matcher(system).replaceAll("").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 지시문 파일의 {{NOW}}를 이번 지시문의 날짜로 채운다 */
    static UnaryOperator<String> promptFromFile(String template) {
        return system -> {
            Matcher m = NOW_LINE.matcher(system);
            String now = m.find() ? m.group(1) : "";
            return template.replace("{{NOW}}", now);
        };
    }

    /** 지금 코드의 지시문을 파일로 뽑는다(날짜 자리는 {{NOW}}). 이걸 복사해 고친 뒤 EVAL_PROMPT_FILE로 잰다 */
    static String templateOf(String system) {
        return NOW_LINE.matcher(system).replaceFirst("지금은 {{NOW}}, 서울 시간이다.");
    }

    static Map<String, Object> summarize(Target target, Options options, String promptHash, List<CaseRun> runs) {
        int n = runs.size();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("at", java.time.OffsetDateTime.now(SEOUL).withNano(0).toString());
        s.put("model", target.label());
        s.put("provider", target.provider());
        s.put("prompt", options.promptLabel());
        s.put("promptHash", promptHash);
        s.put("runs", options.runs());
        s.put("chats", n);
        s.put("verdict", options.verdict());
        s.put("note", options.note());
        // 점수 = 회차별 점수(예약 흐름 질문은 2배)의 평균. 회차 값도 남겨 "93.1 (93.2 / 92.9)"처럼 흔들림을 보인다
        List<Double> perRun = new ArrayList<>();
        List<Long> severePerRun = new ArrayList<>();
        for (int run = 1; run <= options.runs(); run++) {
            int k = run;
            List<CaseRun> these = runs.stream().filter(r -> r.run() == k).toList();
            perRun.add(round1(weighted(these)));
            severePerRun.add(these.stream().mapToLong(r -> r.score().severe().size()).sum());
        }
        s.put("score", round1(perRun.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        s.put("scoreRuns", perRun);
        s.put("severeErrors", round1(severePerRun.stream().mapToLong(Long::longValue).average().orElse(0)));
        s.put("severeRuns", severePerRun);
        List<CaseRun> flow = runs.stream().filter(r -> r.evalCase().flow()).toList();
        s.put("flowCompletion", pct(flow.stream().filter(r -> r.score().pass() && r.score().severe().isEmpty()).count(),
                flow.size()));
        s.put("plainScore", round1(runs.stream().mapToDouble(r -> r.score().points()).average().orElse(0)));
        s.put("passRate", pct(runs.stream().filter(r -> r.score().pass()).count(), n));
        long toolChecked = runs.stream().filter(r -> r.score().toolOk() != null).count();
        s.put("toolAccuracy", pct(runs.stream().filter(r -> Boolean.TRUE.equals(r.score().toolOk())).count(), toolChecked));
        s.put("argAccuracy", pct(runs.stream().mapToInt(r -> r.score().argPassed()).sum(),
                runs.stream().mapToInt(r -> r.score().argTotal()).sum()));
        s.put("replyAccuracy", pct(runs.stream().mapToInt(r -> r.score().replyPassed()).sum(),
                runs.stream().mapToInt(r -> r.score().replyTotal()).sum()));
        s.put("safetyViolations", runs.stream().filter(r -> !r.score().safety().isEmpty()).count());
        s.put("failureRate", pct(runs.stream().filter(r -> r.score().error() != null).count(), n));
        List<Long> millis = runs.stream().filter(r -> r.score().error() == null).map(CaseRun::modelMillis).sorted().toList();
        s.put("latencyAvgMs", millis.isEmpty() ? null : Math.round(millis.stream().mapToLong(Long::longValue).average().orElse(0)));
        s.put("latencyMaxMs", millis.isEmpty() ? null : millis.getLast());
        s.put("latencyMedianMs", percentile(millis, 50));
        s.put("latencyP90Ms", percentile(millis, 90));
        double callsPerChat = n == 0 ? 0 : runs.stream().mapToInt(r -> r.calls().size()).sum() / (double) n;
        s.put("modelCallsPerChat", round1(callsPerChat));
        List<RecordingLlm.Call> known = runs.stream().flatMap(r -> r.calls().stream()).filter(k -> k.usage().known()).toList();
        boolean tokensKnown = !known.isEmpty();
        s.put("inputTokensPerChat", tokensKnown ? Math.round(known.stream().mapToInt(k -> k.usage().inputTokens()).sum() / (double) n) : null);
        s.put("outputTokensPerChat", tokensKnown ? Math.round(known.stream().mapToInt(k -> k.usage().outputTokens()).sum() / (double) n) : null);
        s.put("freeRequestsPerDay", target.freeRpd());
        // 끝까지 간 대화가 하나도 없으면 대화당 호출 수를 모르니 무료 대화 수도 모른다(3.8 Flash 전부 시간 초과, 11/10)
        boolean anyFinished = runs.stream().anyMatch(r -> r.score().error() == null);
        s.put("freeChatsPerDay", target.freeRpd() == null ? null
                : target.freeRpd() < 0 ? -1
                : !anyFinished ? null : (long) Math.floor(target.freeRpd() / Math.max(1.0, callsPerChat)));
        s.put("chatTimeoutSec", target.chatTimeout().toSeconds());
        Map<String, String> perCase = new LinkedHashMap<>();
        Map<String, double[]> byGroup = new LinkedHashMap<>();
        for (CaseRun r : runs) {
            String id = r.evalCase().id();
            long passed = runs.stream().filter(x -> x.evalCase().id().equals(id) && x.score().pass()).count();
            long total = runs.stream().filter(x -> x.evalCase().id().equals(id)).count();
            perCase.put(id, passed + "/" + total);
            double[] g = byGroup.computeIfAbsent(r.evalCase().group(), k -> new double[2]);
            g[0] += r.score().points();
            g[1]++;
        }
        Map<String, Double> groups = new LinkedHashMap<>();
        byGroup.forEach((k, v) -> groups.put(k, round1(v[0] / v[1])));
        s.put("byGroup", groups);
        Map<String, Double> bySet = new LinkedHashMap<>();
        runs.stream().map(r -> r.evalCase().set()).distinct()
                .forEach(set -> bySet.put(set, round1(weighted(runs.stream().filter(r -> r.evalCase().set().equals(set)).toList()))));
        s.put("bySet", bySet);
        s.put("perCase", perCase);
        s.put("unstableCases", perCase.values().stream()
                .filter(v -> !v.startsWith("0/") && !v.split("/")[0].equals(v.split("/")[1])).count());
        return s;
    }

    /** 예약 흐름 질문은 2배로 센 평균 */
    static double weighted(List<CaseRun> runs) {
        double weight = runs.stream().mapToInt(r -> r.evalCase().weight()).sum();
        return weight == 0 ? 0 : runs.stream().mapToDouble(r -> r.score().points() * r.evalCase().weight()).sum() / weight;
    }

    static Long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.clamp(index, 0, sorted.size() - 1));
    }

    private static double pct(long part, long whole) {
        return whole == 0 ? 0 : round1(100.0 * part / whole);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
