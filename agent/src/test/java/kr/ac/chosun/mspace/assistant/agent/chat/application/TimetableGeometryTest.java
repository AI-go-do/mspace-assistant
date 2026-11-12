package kr.ac.chosun.mspace.assistant.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ReadTimetableImage.ClassDraft;
import kr.ac.chosun.mspace.assistant.agent.eval.TimetableScorer;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ImageReader;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 칸 끝을 픽셀로 재기 (TS-18). <b>합성 시간표 그림</b>(eval/timetable/tt01-synthetic, scripts/eval/timetable/make_synthetic.py) + <b>손으로 만든 모델 답</b>으로 검사한다.
 * 실제 사람 시간표는 레포에 두지 않는다. 그림의 과목·강의실·시각은 모두 지어낸 값이다.
 * 손으로 만든 답은 시각을 11/12 실제 캡처에서 본 실수 모양(끝을 더 내려 읽기, 틀린 끝에 다음 칸 붙이기, 30분 늦게 시작)으로 적고,
 * 위치(box_2d)는 위아래로 8~25(0~1000 기준, 최대 약 18분)씩 일부러 흔들었다.
 * 진짜 모델이 얼마나 맞히는지는 이 테스트가 아니라 TimetableEvalRunTest(-Deval=true, 키 필요)가 잰다.
 */
class TimetableGeometryTest {

    final JsonMapper json = JsonMapper.builder().build();
    final ImageReader reader = mock(ImageReader.class);
    final ReadTimetableImage service = new ReadTimetableImage(reader, json);

    @Test
    @DisplayName("합성 시간표: 모델 시각이 틀려도(4/13) 위치로 재면 13칸 모두 정답 시각 (같은 색 붙은 칸·50분 수업 포함)")
    void measuresAllBlocks() throws IOException {
        // given
        byte[] image = resource("tt01-synthetic.png");
        String modelAnswer = new String(resource("tt01-synthetic-hand-made-model-answer.json"), StandardCharsets.UTF_8);
        List<ClassDraft> answers = answers();

        // when
        ReadTimetableImage.Draft modelOnly = service.parse(modelAnswer);
        ReadTimetableImage.Draft measured = service.parse(modelAnswer, image);

        // then
        TimetableScorer.Result before = TimetableScorer.score(modelOnly.classes(), answers);
        TimetableScorer.Result after = TimetableScorer.score(measured.classes(), answers);
        assertThat(before.exact()).as("손 만든 모델 시각: %s", before.misses()).isEqualTo(4);
        assertThat(after.exact()).as("틀린 칸: %s", after.misses()).isEqualTo(13);
        assertThat(after.extra()).isZero();
        assertThat(measured.dropped()).isZero();
    }

    @Test
    @DisplayName("read(): 진짜 흐름에서도 그림을 같이 넘겨 잰다 (PNG)")
    void readUsesImage() throws IOException {
        // given
        byte[] image = resource("tt01-synthetic.png");
        when(reader.read(anyString(), any(), any())).thenReturn(new String(resource("tt01-synthetic-hand-made-model-answer.json"), StandardCharsets.UTF_8));

        // when
        ReadTimetableImage.Draft draft = service.read("image/png", image);

        // then
        assertThat(draft.classes()).contains(
                new ClassDraft("MON", "10:30", "12:00", "자료구조 실습"),
                new ClassDraft("TUE", "15:00", "18:00", "캡스톤디자인"),
                new ClassDraft("WED", "09:00", "09:50", "영어회화"));
    }

    @Test
    @DisplayName("정각 가로줄을 찾아 눈금을 맞춘다: 9시 줄 y=150, 한 시간 80픽셀")
    void calibratesOnGridLines() throws IOException {
        // given
        TimetableGeometry g = TimetableGeometry.of(resource("tt01-synthetic.png")).orElseThrow();
        JsonNode hours = json.readTree(resource("tt01-synthetic-hand-made-model-answer.json")).path("hours");
        List<TimetableGeometry.HourLabel> labels = new java.util.ArrayList<>();
        for (JsonNode h : hours) {
            JsonNode b = h.path("box_2d");
            labels.add(new TimetableGeometry.HourLabel(h.path("hour").asInt(),
                    new TimetableGeometry.Box(b.get(0).asDouble(), b.get(1).asDouble(), b.get(2).asDouble(), b.get(3).asDouble())));
        }

        // when
        TimetableGeometry.Calibration cal = g.calibrate(labels).orElseThrow();

        // then
        assertThat(cal.gridAligned()).isTrue();
        assertThat(cal.slope()).isBetween(79.5, 80.5);
        assertThat(cal.hourAt(150)).isCloseTo(9.0, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    @DisplayName("눈금(hours)이 없거나 그림을 못 열면 재지 않고 모델 시각을 그대로 쓴다 (지금보다 나빠지지 않게)")
    void fallsBackToModelTimes() throws IOException {
        // given
        String modelAnswer = new String(resource("tt01-synthetic-hand-made-model-answer.json"), StandardCharsets.UTF_8);
        JsonNode root = json.readTree(modelAnswer);
        String noHours = json.writeValueAsString(((tools.jackson.databind.node.ObjectNode) root).put("hours", (String) null));
        byte[] notImage = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

        // when
        List<ClassDraft> a = service.parse(noHours, resource("tt01-synthetic.png")).classes();
        List<ClassDraft> b = service.parse(modelAnswer, notImage).classes();
        List<ClassDraft> modelOnly = service.parse(modelAnswer).classes();

        // then
        assertThat(a).isEqualTo(modelOnly);
        assertThat(b).isEqualTo(modelOnly);
    }

    @Test
    @DisplayName("상자가 색칠 안 된 곳(금요일 빈칸)을 가리키면 그 칸만 모델 시각")
    void emptyBoxKeepsModelTime() throws IOException {
        // given
        String answer = """
                {"hours":%s,
                 "classes":[{"dayOfWeek":"FRI","startTime":"09:00","endTime":"10:00","name":"없는 수업","box_2d":[140,810,220,970]}]}"""
                .formatted(json.writeValueAsString(json.readTree(resource("tt01-synthetic-hand-made-model-answer.json")).path("hours")));

        // when
        List<ClassDraft> out = service.parse(answer, resource("tt01-synthetic.png")).classes();

        // then
        assertThat(out).containsExactly(new ClassDraft("FRI", "09:00", "10:00", "없는 수업"));
    }

    @Test
    @DisplayName("같은 색 수업 두 개가 1픽셀 흰 경계로 붙어 있어도 합치지 않는다 (글자 끊김과 구분)")
    void sameColorNeighborsStaySeparate() throws IOException {
        // given: 한 시간 40픽셀, 8시 줄 y=40. 월요일 칸에 같은 색 9:00~10:30, 10:30~12:00 + 첫 칸 안에 흰 글자
        int w = 400;
        int h = 600;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(230, 230, 230));
        for (int hour = 8; hour <= 20; hour++) {
            g.drawLine(0, 40 + (hour - 8) * 40, w, 40 + (hour - 8) * 40);
        }
        g.setColor(new Color(240, 135, 120));
        g.fillRect(50, 81, 100, 59);   // 9:00(y80) 다음 줄부터 139까지
        g.fillRect(50, 141, 100, 59);  // 140은 흰 경계, 10:30~12:00
        g.setColor(Color.WHITE);
        g.fillRect(58, 92, 70, 12);    // 글자 자리(흰색)
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        StringBuilder hours = new StringBuilder("[");
        for (int hour = 8; hour <= 18; hour++) {
            double y = (40 + (hour - 8) * 40 + 4) / 600.0 * 1000;
            hours.append(hour > 8 ? "," : "").append("{\"hour\":").append(hour).append(",\"box_2d\":[")
                    .append(Math.round(y)).append(",10,").append(Math.round(y + 15)).append(",40]}");
        }
        hours.append("]");
        // 모델 상자는 일부러 아래로 흔듦(첫 칸 아래 끝을 둘째 칸 안까지)
        String answer = """
                {"hours":%s,"classes":[
                  {"dayOfWeek":"MON","startTime":"09:00","endTime":"11:00","name":"자료구조","box_2d":[140,125,250,375]},
                  {"dayOfWeek":"MON","startTime":"11:00","endTime":"12:00","name":"자료구조 실습","box_2d":[240,125,333,375]}]}"""
                .formatted(hours);

        // when
        List<ClassDraft> out = service.parse(answer, png.toByteArray()).classes();

        // then
        assertThat(out).containsExactly(
                new ClassDraft("MON", "09:00", "10:30", "자료구조"),
                new ClassDraft("MON", "10:30", "12:00", "자료구조 실습"));
    }

    @Test
    @DisplayName("픽셀이 너무 많은 그림(압축은 작아도)은 풀지 않고 재기를 건너뛴다 (메모리 보호)")
    void hugeImageIsNotDecoded() throws IOException {
        // given: 4100×4100 흑백 PNG. 파일은 작지만 픽셀은 1,681만 개로 상한(1,600만) 초과
        BufferedImage img = new BufferedImage(4100, 4100, BufferedImage.TYPE_BYTE_BINARY);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);

        // when
        var geometry = TimetableGeometry.of(png.toByteArray());

        // then
        assertThat(png.size()).isLessThan(ReadTimetableImage.MAX_BYTES);
        assertThat(geometry).isEmpty();
    }

    private List<ClassDraft> answers() throws IOException {
        JsonNode root = json.readTree(resource("tt01-synthetic.answer.json"));
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
