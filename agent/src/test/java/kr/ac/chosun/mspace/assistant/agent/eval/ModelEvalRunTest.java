package kr.ac.chosun.mspace.assistant.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini.GeminiLlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini.GeminiProperties;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.ollama.OllamaLlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.ollama.OllamaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

/**
 * 진짜 모델로 평가를 돌리는 입구. 모델 키·젯슨이 있어야 해서 EVAL_MODELS와 -Deval=true를 다 넣었을 때만 돈다(환경 변수만 남아 있을 때 일반 빌드에서 돌지 않게)(CI의 빌드 검사가 아니라 측정 도구).
 * CI에서는 ModelEvalTest가 같은 채점기를 가짜 모델로 검사한다. 쓰는 법은 scripts/eval/README.md.
 *
 * <pre>
 * read -rs "GEMINI_API_KEY?Gemini 키: " && export GEMINI_API_KEY   (zsh. bash는 read -rs -p "Gemini 키: " GEMINI_API_KEY)
 * EVAL_MODELS=gemini:gemini-3.5-flash-lite ./mvnw -q -pl agent test -Dtest=ModelEvalRunTest -Deval=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
class ModelEvalRunTest {

    /** AI Studio 한도 화면 실측(11/5, 무료 등급, 영준 계정). 다른 모델은 EVAL_RPM·EVAL_RPD로 넣는다 */
    static final Map<String, int[]> GEMINI_FREE = Map.of(
            "gemini-3.5-flash-lite", new int[]{15, 500},
            "gemini-3.8-flash", new int[]{5, 20});

    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_MODELS", matches = ".+")
    @EnabledIfSystemProperty(named = "eval", matches = "true")
    @DisplayName("진짜 모델 평가 (EVAL_MODELS가 있을 때만)")
    void run() throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        EvalCase.Suite suite = EvalCase.load(json);
        EvalRunner runner = new EvalRunner(json);
        // 두 번 채점해 평균(회차 값도 표에 보임). 한 번은 운이다
        int runs = Integer.parseInt(env("EVAL_RUNS", "2"));
        List<String> only = env("EVAL_ONLY", "").isBlank() ? List.of() : Arrays.asList(env("EVAL_ONLY", "").split(","));
        String promptFile = env("EVAL_PROMPT_FILE", "");
        UnaryOperator<String> prompt = promptFile.isBlank() ? UnaryOperator.identity()
                : EvalRunner.promptFromFile(Files.readString(Path.of(promptFile), StandardCharsets.UTF_8));
        String promptLabel = env("EVAL_PROMPT_LABEL", promptFile.isBlank() ? "현재 코드" : Path.of(promptFile).getFileName().toString());
        // 판단: 지금 운영 중인 판(현재 코드 = 지시문 v1)이 기준, 나머지는 비교로 시작해 결과를 보고 채택·탈락으로 고친다
        String verdict = env("EVAL_VERDICT", promptFile.isBlank() ? "기준" : "비교");
        EvalRunner.Options options = new EvalRunner.Options(runs, only, promptLabel, prompt, verdict, env("EVAL_NOTE", ""));

        List<EvalRunner.Report> reports = new ArrayList<>();
        for (String spec : env("EVAL_MODELS", "").split(",")) {
            reports.add(runner.run(target(spec.strip(), json), suite, options));
        }
        Path out = Path.of(env("EVAL_OUT", "../scripts/eval/results"));
        String stamp = OffsetDateTime.now(EvalRunner.SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"));
        Path md = ReportWriter.write(out, stamp, reports, json);
        assertThat(md).exists();
        assertThat(reports).allSatisfy(r -> assertThat(r.runs()).isNotEmpty());
        System.out.println("\n결과: " + md.toAbsolutePath());
        reports.forEach(r -> System.out.println(r.summary()));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_DUMP_PROMPT", matches = ".+")
    @EnabledIfSystemProperty(named = "eval", matches = "true")
    @DisplayName("지금 코드의 지시문을 파일로 뽑기 (EVAL_DUMP_PROMPT=경로). 고친 판을 EVAL_PROMPT_FILE로 잰다")
    void dumpPrompt() throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        String system = EvalRunner.currentPrompt(json);
        Path out = Path.of(System.getenv("EVAL_DUMP_PROMPT"));
        // 고치던 지시문을 덮어쓰지 않는다 (CREATE_NEW: 이미 있으면 실패)
        Files.writeString(out, EvalRunner.templateOf(system), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        assertThat(Files.readString(out, StandardCharsets.UTF_8)).contains("{{NOW}}");
        System.out.println("지시문 " + EvalRunner.promptHash(system) + " → " + out.toAbsolutePath());
    }

    /** "gemini:모델" 또는 "ollama:모델:태그" (앞의 콜론 하나로만 나눈다) */
    static EvalRunner.Target target(String spec, JsonMapper json) {
        int colon = spec.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("EVAL_MODELS는 gemini:모델 또는 ollama:모델 모양이에요: " + spec);
        }
        String provider = spec.substring(0, colon);
        String model = spec.substring(colon + 1);
        return switch (provider) {
            case "gemini" -> {
                int[] free = GEMINI_FREE.getOrDefault(model, new int[]{10, -2});
                int rpm = Integer.parseInt(env("EVAL_RPM", String.valueOf(free[0])));
                Integer rpd = env("EVAL_RPD", "").isBlank() ? (free[1] == -2 ? null : free[1]) : Integer.valueOf(env("EVAL_RPD", ""));
                var client = new GeminiLlmClient(new GeminiProperties(null, model, env("GEMINI_API_KEY", ""),
                        Duration.ofSeconds(20)), json);
                yield new EvalRunner.Target(model, provider, client, 60_000L / rpm + 200, Duration.ofSeconds(45), rpd);
            }
            case "ollama" -> {
                // 젯슨(GPU 공유 메모리 8GB)은 첫 호출에 모델을 올리느라 느리다. 상한을 늘려 재고 표에 적는다
                Duration timeout = Duration.ofSeconds(Long.parseLong(env("EVAL_OLLAMA_TIMEOUT", "120")));
                var client = new OllamaLlmClient(new OllamaProperties(env("OLLAMA_BASE_URL", "http://localhost:11434"), model,
                        timeout, Duration.ofSeconds(5)));
                yield new EvalRunner.Target("ollama " + model, provider, client, 0,
                        Duration.ofSeconds(Long.parseLong(env("EVAL_CHAT_TIMEOUT", "180"))), -1);
            }
            default -> throw new IllegalArgumentException("모르는 제공자: " + provider);
        };
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null ? fallback : v;
    }
}
