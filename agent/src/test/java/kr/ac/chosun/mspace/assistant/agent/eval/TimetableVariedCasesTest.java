package kr.ac.chosun.mspace.assistant.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ReadTimetableImage.ClassDraft;
import kr.ac.chosun.mspace.assistant.agent.chat.application.TimetableEvalAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 여러 모양 시간표에서 픽셀 재기가 맞는지 (TS-19). 키 없이 매번 돈다.
 * <p>
 * 사진: eval/timetable-varied/tvNN-*.png|jpg (scripts/eval/timetable/make_varied.py, 크기·잘림·다크·옅은 색·토요일·8시 시작·눈금 위치를 섞음)
 * + eval/timetable/tt01-synthetic. 모델 답은 진짜가 아니라 실제 모델 실수를 흉내 낸 답(*.sim-model-answer.json, tt01은 hand-made)이다.
 * 레포 밖 사진(실제 캡처)은 EVAL_TIMETABLE_DIR에 같은 규칙(ttNN.jpg + .answer.json + -hand-made-model-answer.json)으로 두면 함께 재고,
 * 결과에는 순번(outside-N)과 점수만 쓴다.
 * <p>
 * 점수 = 시작·끝·요일이 정답과 같은 칸 / 정답 칸. 줄마다 "모델 시각 그대로"와 "픽셀 재기"를 나란히 낸다.
 */
class TimetableVariedCasesTest {

    /** 사진마다 픽셀 재기가 이만큼은 맞혀야 한다(정답 칸 중 비율) */
    static final double MIN_PER_IMAGE = 0.9;

    final JsonMapper json = JsonMapper.builder().build();
    final TimetableEvalAccess service = new TimetableEvalAccess(json);

    record Row(String label, TimetableScorer.Result model, TimetableScorer.Result measured) {
    }

    @Test
    @DisplayName("여러 모양 시간표(크기·잘림·다크·옅은 색·토요일·8시 시작·눈금 위치): 사진마다 픽셀 재기가 90% 이상 맞힌다")
    void variedImages() throws IOException, URISyntaxException {
        // given
        List<Path> images = new ArrayList<>();
        images.addAll(list(resourceDir("/eval/timetable"), "tt\\d+[^.]*\\.(png|jpg)"));
        images.addAll(list(resourceDir("/eval/timetable-varied"), "tv\\d+[^.]*\\.(png|jpg)"));
        String outsideDir = System.getenv("EVAL_TIMETABLE_DIR");
        int repoCount = images.size();
        if (outsideDir != null && !outsideDir.isBlank()) {
            images.addAll(list(Path.of(outsideDir), "tt\\d+[^.]*\\.(png|jpg)"));
        }

        // when
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            Path image = images.get(i);
            String id = image.getFileName().toString().replaceFirst("\\.(jpg|png)$", "");
            Path modelFile = Files.exists(image.resolveSibling(id + ".sim-model-answer.json"))
                    ? image.resolveSibling(id + ".sim-model-answer.json")
                    : image.resolveSibling(id + "-hand-made-model-answer.json");
            if (!Files.exists(modelFile)) {
                continue;
            }
            String modelText = Files.readString(modelFile, StandardCharsets.UTF_8);
            List<ClassDraft> answers = answers(image.resolveSibling(id + ".answer.json"));
            String label = i < repoCount ? id : "outside-" + (i - repoCount + 1);
            rows.add(new Row(label,
                    TimetableScorer.score(service.modelTimes(modelText), answers),
                    TimetableScorer.score(service.measured(modelText, Files.readAllBytes(image)), answers)));
        }

        // then
        StringBuilder table = new StringBuilder("| 사진 | 모델 시각 그대로 | 픽셀 재기 |\n|---|---|---|\n");
        int[] sum = new int[3];
        for (Row r : rows) {
            table.append("| ").append(r.label()).append(" | ").append(r.model().exact()).append('/').append(r.model().answers())
                    .append(" | ").append(r.measured().exact()).append('/').append(r.measured().answers()).append(" |\n");
            sum[0] += r.model().exact();
            sum[1] += r.measured().exact();
            sum[2] += r.measured().answers();
        }
        table.append("| **합계** | **").append(sum[0]).append('/').append(sum[2]).append("** | **").append(sum[1]).append('/').append(sum[2]).append("** |\n");
        System.out.println("\n시간표 여러 모양 평가\n" + table);
        assertThat(rows).hasSizeGreaterThanOrEqualTo(13);
        org.assertj.core.api.SoftAssertions soft = new org.assertj.core.api.SoftAssertions();
        for (Row r : rows) {
            boolean outside = r.label().startsWith("outside-");
            soft.assertThat((double) r.measured().exact() / r.measured().answers())
                    .as("%s 틀린 칸: %s", r.label(), outside ? r.measured().misses().size() + "개" : r.measured().misses())
                    .isGreaterThanOrEqualTo(MIN_PER_IMAGE);
            soft.assertThat(r.measured().extra()).as(r.label()).isZero();
        }
        soft.assertAll();
    }

    private static Path resourceDir(String name) throws URISyntaxException {
        return Path.of(TimetableVariedCasesTest.class.getResource(name).toURI());
    }

    private static List<Path> list(Path dir, String pattern) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches(pattern)).sorted().toList();
        }
    }

    private List<ClassDraft> answers(Path file) throws IOException {
        JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
        List<ClassDraft> out = new ArrayList<>();
        for (JsonNode c : root.path("classes")) {
            out.add(new ClassDraft(c.path("dayOfWeek").asString(), c.path("startTime").asString(), c.path("endTime").asString(), c.path("name").asString()));
        }
        return out;
    }
}
