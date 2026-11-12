package kr.ac.chosun.mspace.assistant.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ReadTimetableImage.ClassDraft;
import kr.ac.chosun.mspace.assistant.agent.eval.TimetableScorer;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ImageReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 칸 끝을 픽셀로 재기 (TS-18). 진짜 캡처(eval/timetable/tt01) + <b>손으로 만든 모델 답</b>으로 검사한다.
 * 손으로 만든 답은 시각을 11/12 실제로 틀리게 읽은 값 그대로 두고, 위치(box_2d)는 위아래로 8~25(0~1000 기준, 최대 약 19분)씩 일부러 흔들었다.
 * 진짜 모델이 얼마나 맞히는지는 이 테스트가 아니라 TimetableEvalRunTest(-Deval=true, 키 필요)가 잰다.
 */
class TimetableGeometryTest {

    final JsonMapper json = JsonMapper.builder().build();
    final ImageReader reader = mock(ImageReader.class);
    final ReadTimetableImage service = new ReadTimetableImage(reader, json);

    @Test
    @DisplayName("tt01: 모델 시각이 틀려도(5/11) 위치로 재면 11칸 모두 정답 시각")
    void measuresAllBlocks() throws IOException {
        // given
        byte[] image = resource("tt01-everytime.jpg");
        String modelAnswer = new String(resource("tt01-hand-made-model-answer.json"), StandardCharsets.UTF_8);
        List<ClassDraft> answers = answers();

        // when
        ReadTimetableImage.Draft modelOnly = service.parse(modelAnswer);
        ReadTimetableImage.Draft measured = service.parse(modelAnswer, image);

        // then
        TimetableScorer.Result before = TimetableScorer.score(modelOnly.classes(), answers);
        TimetableScorer.Result after = TimetableScorer.score(measured.classes(), answers);
        assertThat(before.exact()).as("11/12 화면에서 본 결과 재현: %s", before.misses()).isEqualTo(5);
        assertThat(after.exact()).as("틀린 칸: %s", after.misses()).isEqualTo(11);
        assertThat(after.extra()).isZero();
        assertThat(measured.dropped()).isZero();
    }

    @Test
    @DisplayName("read(): 진짜 흐름에서도 그림을 같이 넘겨 잰다 (JPEG)")
    void readUsesImage() throws IOException {
        byte[] image = resource("tt01-everytime.jpg");
        when(reader.read(anyString(), any(), any())).thenReturn(new String(resource("tt01-hand-made-model-answer.json"), StandardCharsets.UTF_8));

        ReadTimetableImage.Draft draft = service.read("image/jpeg", image);

        assertThat(draft.classes()).contains(
                new ClassDraft("WED", "13:00", "14:30", "근로"),
                new ClassDraft("TUE", "15:00", "18:00", "영상문화콘텐츠 기획 개발"),
                new ClassDraft("MON", "17:00", "18:00", "인하인스타"));
    }

    @Test
    @DisplayName("정각 가로줄을 찾아 눈금을 맞춘다: 8시~18시 줄, 한 시간 약 42.5픽셀")
    void calibratesOnGridLines() throws IOException {
        TimetableGeometry g = TimetableGeometry.of(resource("tt01-everytime.jpg")).orElseThrow();
        JsonNode hours = json.readTree(resource("tt01-hand-made-model-answer.json")).path("hours");
        List<TimetableGeometry.HourLabel> labels = new java.util.ArrayList<>();
        for (JsonNode h : hours) {
            JsonNode b = h.path("box_2d");
            labels.add(new TimetableGeometry.HourLabel(h.path("hour").asInt(),
                    new TimetableGeometry.Box(b.get(0).asDouble(), b.get(1).asDouble(), b.get(2).asDouble(), b.get(3).asDouble())));
        }

        TimetableGeometry.Calibration cal = g.calibrate(labels).orElseThrow();

        assertThat(cal.gridAligned()).isTrue();
        assertThat(cal.slope()).isBetween(42.0, 43.0);
        assertThat(cal.hourAt(79.5)).isCloseTo(8.0, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    @DisplayName("눈금(hours)이 없거나 그림을 못 열면 재지 않고 모델 시각을 그대로 쓴다 (지금보다 나빠지지 않게)")
    void fallsBackToModelTimes() throws IOException {
        String modelAnswer = new String(resource("tt01-hand-made-model-answer.json"), StandardCharsets.UTF_8);
        JsonNode root = json.readTree(modelAnswer);
        String noHours = json.writeValueAsString(((tools.jackson.databind.node.ObjectNode) root).put("hours", (String) null));
        byte[] notImage = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

        List<ClassDraft> a = service.parse(noHours, resource("tt01-everytime.jpg")).classes();
        List<ClassDraft> b = service.parse(modelAnswer, notImage).classes();

        List<ClassDraft> modelOnly = service.parse(modelAnswer).classes();
        assertThat(a).isEqualTo(modelOnly);
        assertThat(b).isEqualTo(modelOnly);
    }

    @Test
    @DisplayName("상자가 색칠 안 된 곳(금요일 빈칸)을 가리키면 그 칸만 모델 시각")
    void emptyBoxKeepsModelTime() throws IOException {
        String answer = """
                {"hours":%s,
                 "classes":[{"dayOfWeek":"FRI","startTime":"09:00","endTime":"10:00","name":"없는 수업","box_2d":[150,810,240,970]}]}"""
                .formatted(json.writeValueAsString(json.readTree(resource("tt01-hand-made-model-answer.json")).path("hours")));

        List<ClassDraft> out = service.parse(answer, resource("tt01-everytime.jpg")).classes();

        assertThat(out).containsExactly(new ClassDraft("FRI", "09:00", "10:00", "없는 수업"));
    }

    private List<ClassDraft> answers() throws IOException {
        JsonNode root = json.readTree(resource("tt01-everytime.answer.json"));
        List<ClassDraft> out = new java.util.ArrayList<>();
        for (JsonNode c : root.path("classes")) {
            out.add(new ClassDraft(c.path("dayOfWeek").asString(), c.path("startTime").asString(), c.path("endTime").asString(), c.path("name").asString()));
        }
        return out;
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream in = TimetableGeometryTest.class.getResourceAsStream("/eval/timetable/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }
}
