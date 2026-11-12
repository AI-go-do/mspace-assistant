package kr.ac.chosun.mspace.assistant.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ReadTimetableImage.ClassDraft;
import kr.ac.chosun.mspace.assistant.agent.chat.application.TimetableEvalAccess;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini.GeminiImageReader;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini.GeminiProperties;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시간표 사진 읽기를 진짜 Gemini로 채점한다 (TS-18). 사진 한 장·한 회차마다 호출 2번:
 * <ol>
 *   <li><b>v1</b>: 예전 지시문(prompts/timetable-v1.txt), 모델 시각 그대로 = 기준</li>
 *   <li><b>v2</b>: 새 지시문(위치 box_2d 같이 받기). 같은 답으로 두 줄을 낸다
 *     <ul><li>v2-모델 시각: 지시문만 바꾼 효과</li><li>v2+픽셀 재기: 지시문 + 코드 보정 = 지금 코드</li></ul></li>
 * </ol>
 * 한 판에 한 가지만 바꾼 줄이 나란히 나오게 했다. 정답·사진은 agent/src/test/resources/eval/timetable/ (ttNN*.png|jpg + 같은 이름.answer.json, 합성 그림만).
 * 실제 캡처처럼 레포에 둘 수 없는 사진은 EVAL_TIMETABLE_DIR(레포 밖 폴더)에 같은 규칙으로 두면 함께 잰다.
 *
 * <pre>
 * read -rs "GEMINI_API_KEY?Gemini 키: " && export GEMINI_API_KEY
 * EVAL_TIMETABLE=1 ./mvnw -q -pl agent test -Dtest=TimetableEvalRunTest -Deval=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * 사진 1장 × EVAL_RUNS(기본 2) × 2 = 호출 4번. 무료 한도(하루 500회)를 다른 평가와 같이 쓰니 회차를 늘릴 때 주의.
 */
class TimetableEvalRunTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_TIMETABLE", matches = ".+")
    @EnabledIfSystemProperty(named = "eval", matches = "true")
    @DisplayName("시간표 사진 읽기 진짜 모델 평가 (EVAL_TIMETABLE이 있을 때만)")
    void run() throws IOException, URISyntaxException, InterruptedException {
        JsonMapper json = JsonMapper.builder().build();
        String model = env("EVAL_TIMETABLE_MODEL", "gemini-3.5-flash-lite");
        GeminiImageReader reader = new GeminiImageReader(new GeminiProperties(null, model, System.getenv("GEMINI_API_KEY"), Duration.ofSeconds(60)));
        TimetableEvalAccess service = new TimetableEvalAccess(json);
        String v1 = Files.readString(Path.of(env("EVAL_TIMETABLE_V1", "../scripts/eval/prompts/timetable-v1.txt")), StandardCharsets.UTF_8);
        int runs = Integer.parseInt(env("EVAL_RUNS", "2"));

        List<Path> dirs = new ArrayList<>(List.of(Path.of(TimetableEvalRunTest.class.getResource("/eval/timetable").toURI())));
        if (!env("EVAL_TIMETABLE_DIR", "").isBlank()) {
            dirs.add(Path.of(env("EVAL_TIMETABLE_DIR", "")));
        }
        List<Path> images = new ArrayList<>();
        for (Path dir : dirs) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().matches("tt\\d+[^.]*\\.(jpg|png)")).sorted().forEach(images::add);
            }
        }
        assertThat(images).isNotEmpty();

        // 줄 이름 → 회차마다 (정확, 30분 안, 정답 수, 빠짐, 덤)
        Map<String, List<TimetableScorer.Result>> rows = new LinkedHashMap<>();
        Map<String, Integer> failures = new LinkedHashMap<>();
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Path image : images) {
            String id = image.getFileName().toString().replaceFirst("\\.(jpg|png)$", "");
            byte[] bytes = Files.readAllBytes(image);
            String mime = image.toString().endsWith(".png") ? "image/png" : "image/jpeg";
            List<ClassDraft> answers = answers(json, image.resolveSibling(id + ".answer.json"));
            for (int run = 1; run <= runs; run++) {
                String t1 = call(reader, v1, mime, bytes);
                Thread.sleep(5000);  // 분당 한도(15회) 여유
                String t2 = call(reader, service.instruction(), mime, bytes);
                Thread.sleep(5000);
                // 한도·장애로 답이 없으면 0점으로 섞지 않고 실패로 따로 센다(모델이 못 읽은 것과 구분)
                add(rows, failures, detail, id, run, "v1 · 모델 시각 (기준)", t1 == null ? null : service.modelTimes(t1), answers, t1);
                add(rows, failures, detail, id, run, "v2 · 모델 시각 (지시문만)", t2 == null ? null : service.modelTimes(t2), answers, t2);
                add(rows, failures, detail, id, run, "v2 · 픽셀 재기 (지금 코드)", t2 == null ? null : service.measured(t2, bytes), answers, null);
            }
        }

        Path out = Path.of(env("EVAL_OUT", "../scripts/eval/results"));
        Files.createDirectories(out);
        String stamp = OffsetDateTime.now(EvalRunner.SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"));
        StringBuilder md = new StringBuilder("# 시간표 사진 읽기 평가 " + stamp + "\n\n")
                .append("모델 `").append(model).append("` · 사진 ").append(images.size()).append("장 · ").append(runs).append("회차. 점수 = 시작·끝이 정답과 같은 칸 / 정답 칸\n\n")
                .append("실패 = 한도·장애로 답이 없던 회차. 점수 평균에 넣지 않는다\n\n")
                .append("| 방식 | 점수 (회차) | 30분 안 | 빠짐 | 덤 | 실패 |\n|---|---|---|---|---|---|\n");
        for (String mode : failures.keySet()) {
            rows.computeIfAbsent(mode, k -> new ArrayList<>());
        }
        for (var e : rows.entrySet()) {
            List<TimetableScorer.Result> rs = e.getValue();
            String avg = rs.isEmpty() ? "—" : String.format("%.1f", rs.stream().mapToDouble(TimetableScorer.Result::score).average().orElse(0));
            md.append("| ").append(e.getKey()).append(" | **").append(avg).append("** (")
                    .append(String.join(" / ", rs.stream().map(r -> String.valueOf(r.score())).toList())).append(") | ")
                    .append(rs.stream().mapToInt(TimetableScorer.Result::within30).sum()).append("/").append(rs.stream().mapToInt(TimetableScorer.Result::answers).sum()).append(" | ")
                    .append(rs.stream().mapToInt(TimetableScorer.Result::missing).sum()).append(" | ")
                    .append(rs.stream().mapToInt(TimetableScorer.Result::extra).sum()).append(" | ")
                    .append(failures.getOrDefault(e.getKey(), 0)).append(" |\n");
        }
        md.append("\n## 틀린 칸\n");
        for (Map<String, Object> d : detail) {
            md.append("- ").append(d.get("image")).append(" ").append(d.get("run")).append("회 ").append(d.get("mode")).append(": ").append(d.get("misses")).append('\n');
        }
        Path report = out.resolve("timetable-" + stamp + ".md");
        Files.writeString(report, md, StandardCharsets.UTF_8);
        Files.writeString(out.resolve("timetable-" + stamp + "-detail.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(detail), StandardCharsets.UTF_8);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", stamp);
        line.put("model", model);
        line.put("runs", runs);
        rows.forEach((k, v) -> line.put(k, v.stream().map(TimetableScorer.Result::score).toList()));
        line.put("failures", failures);
        Files.writeString(out.resolve("timetable-history.jsonl"), json.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("\n결과: " + report.toAbsolutePath() + "\n" + md);
    }

    private static String call(GeminiImageReader reader, String instruction, String mime, byte[] bytes) {
        try {
            return reader.read(instruction, mime, bytes);
        } catch (LlmException e) {
            System.out.println("모델 호출 실패: " + e.code());
            return null;
        }
    }

    private static void add(Map<String, List<TimetableScorer.Result>> rows, Map<String, Integer> failures, List<Map<String, Object>> detail,
            String image, int run, String mode, List<ClassDraft> predicted, List<ClassDraft> answers, String raw) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("image", image);
        d.put("run", run);
        d.put("mode", mode);
        if (predicted == null) {
            failures.merge(mode, 1, Integer::sum);
            d.put("failed", true);
            d.put("misses", List.of("모델 호출 실패(한도·장애)"));
            detail.add(d);
            return;
        }
        TimetableScorer.Result r = TimetableScorer.score(predicted, answers);
        rows.computeIfAbsent(mode, k -> new ArrayList<>()).add(r);
        d.put("score", r.score());
        d.put("misses", r.misses());
        if (raw != null) {
            d.put("raw", raw);  // 모델 답 원문(과목명·시각·위치만. 사진에 이름·학번 없음)
        }
        detail.add(d);
    }

    private static List<ClassDraft> answers(JsonMapper json, Path file) throws IOException {
        JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
        List<ClassDraft> out = new ArrayList<>();
        for (JsonNode c : root.path("classes")) {
            out.add(new ClassDraft(c.path("dayOfWeek").asString(), c.path("startTime").asString(), c.path("endTime").asString(), c.path("name").asString()));
        }
        return out;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
