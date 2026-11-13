package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ImageReader;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시간표 캡처(에브리타임 등)를 모델에 읽혀 {@code classes} 초안으로 바꾼다 (설계 5장 다음 단계 ①).
 * <ul>
 *   <li>저장하지 않는다. 화면이 확인 카드로 보여 주고 학생이 고친 뒤 PUT /api/agent/schedule로 넣는다</li>
 *   <li>모델 답은 믿지 않는다: 요일·시각 형식을 검사해 틀린 줄은 버리고 {@code dropped}로 센다</li>
 *   <li>그림에 이름·학번이 있어도 모델에게 적지 말라고 지시하고, 결과에서도 과목명만 쓴다</li>
 * </ul>
 */
public class ReadTimetableImage {

    static final Set<String> MIME_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    static final int MAX_BYTES = 5 * 1024 * 1024;
    /** mcp-server PUT 검사(max-classes 40, max-name-chars 30)와 같게. 초안이 통과하고 저장이 거절되면 학생이 원인을 모른다 */
    static final int MAX_CLASSES = 40;
    static final int MAX_NAME = 30;

    private static final Set<String> DAYS = Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 지시문 v2 (11/12). v1은 시각만 받아 칸 끝을 다음 칸 시작에 붙이거나 30분씩 밀렸다(TS-18).
     * v2는 칸·눈금 글자의 위치(box_2d)를 같이 받아 코드가 픽셀로 끝을 잰다({@link TimetableGeometry}).
     * 시각(startTime·endTime)은 위치로 못 잴 때 쓰는 예비 값이다.
     */
    static final String INSTRUCTION = """
            너는 대학생 시간표 캡처 화면을 읽어 JSON으로 옮기는 도우미다.
            JSON 외의 글은 쓰지 않는다. 형식:
            {"hours":[{"hour":9,"box_2d":[0,0,0,0]}],
             "classes":[{"dayOfWeek":"MON","startTime":"09:00","endTime":"10:30","name":"과목명","box_2d":[0,0,0,0]}]}
            - box_2d: 그림 안 위치 [ymin, xmin, ymax, xmax]. 그림 전체를 0~1000으로 본 정수
            - hours: 왼쪽 시간 눈금 숫자마다 하나. hour는 24시간으로 적는다(12 다음의 1, 2, 3 …은 13, 14, 15 …). box_2d는 그 숫자 글자의 위치
            - classes: 색칠된 수업 칸마다 하나. box_2d는 색칠된 칸 전체(색이 시작하는 위 끝부터 끝나는 아래 끝까지)
            - dayOfWeek: MON TUE WED THU FRI SAT SUN 중 하나 (월=MON, 화=TUE, 수=WED, 목=THU, 금=FRI, 토=SAT, 일=SUN). 칸이 놓인 열의 머리 글자로 정한다
            - startTime, endTime: 24시간 HH:mm. 칸마다 위 끝·아래 끝을 눈금에 맞춰 따로 읽는다. 칸이 붙어 있어도 다음 칸 시작을 끝으로 쓰지 않는다. 눈금 한 칸 안에서 반쯤이면 :30
            - name: 과목명만. 교수 이름, 강의실, 학번, 사람 이름은 절대 적지 않는다
            - 같은 과목이 여러 요일에 있으면 요일마다 한 항목씩
            - 눈금 숫자를 못 찾으면 hours는 []로 두고 classes는 읽은 대로 적는다. 수업 칸을 하나도 못 읽으면 {"hours":[],"classes":[]}
            """;

    private final ImageReader reader;
    private final JsonMapper json;

    public ReadTimetableImage(ImageReader reader, JsonMapper json) {
        this.reader = reader;
        this.json = json;
    }

    /**
     * @throws InvalidImage 형식·크기가 맞지 않음 (400)
     * @throws kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException 모델 실패 (503/429)
     */
    public Draft read(String mimeType, byte[] image) {
        if (image == null || image.length == 0) {
            throw new InvalidImage("시간표 사진을 올려 주세요.");
        }
        if (mimeType == null || !MIME_TYPES.contains(mimeType.toLowerCase(Locale.ROOT))) {
            throw new InvalidImage("PNG, JPEG, WebP 사진만 읽을 수 있어요.");
        }
        if (image.length > MAX_BYTES) {
            throw new InvalidImage("사진은 5MB까지 올릴 수 있어요.");
        }
        String mime = mimeType.toLowerCase(Locale.ROOT);
        if (!looksLike(mime, image)) {
            throw new InvalidImage("사진 파일이 아니에요. PNG, JPEG, WebP 사진을 올려 주세요.");
        }
        String text = reader.read(INSTRUCTION, mime, image);
        return parse(text, image);
    }

    /** 선언된 형식과 실제 바이트(매직 넘버)가 맞는지. 아무 바이트나 외부 AI로 보내지 않기 위해 */
    static boolean looksLike(String mime, byte[] b) {
        return switch (mime) {
            case "image/png" -> startsWith(b, 0, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            case "image/jpeg" -> startsWith(b, 0, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "image/webp" -> startsWith(b, 0, new byte[] {'R', 'I', 'F', 'F'}) && startsWith(b, 8, new byte[] {'W', 'E', 'B', 'P'});
            default -> false;
        };
    }

    private static boolean startsWith(byte[] b, int offset, byte[] prefix) {
        if (b.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (b[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** 위치로 재지 않고 모델이 적은 시각만 쓴다 (v1 방식, 평가 비교용) */
    Draft parse(String text) {
        return parse(text, null);
    }

    /**
     * @param image 원본 그림. 있으면 칸 위치(box_2d)로 위·아래 끝을 픽셀로 재서 모델 시각을 고친다. 못 재는 칸은 모델 시각 그대로
     */
    Draft parse(String text, byte[] image) {
        JsonNode root;
        try {
            root = json.readTree(stripFence(text));
        } catch (JacksonException e) {
            return new Draft(List.of(), 0, "시간표를 읽지 못했어요. 더 선명한 사진으로 다시 올리거나 직접 입력해 주세요.");
        }
        JsonNode classes = root.path("classes");
        Measurer measurer = Measurer.of(image, root.path("hours"), classes);
        List<ClassDraft> out = new ArrayList<>();
        int dropped = 0;
        if (classes.isArray()) {
            for (JsonNode c : classes) {
                ClassDraft d = normalize(c, measurer);
                if (d == null || out.contains(d) || out.size() >= MAX_CLASSES) {
                    dropped++;  // 틀린 줄·중복·한도 초과 모두 "읽지 못한 칸"으로 알린다
                } else {
                    out.add(d);
                }
            }
        }
        String note;
        if (out.isEmpty()) {
            note = "시간표를 읽지 못했어요. 더 선명한 사진으로 다시 올리거나 직접 입력해 주세요.";
        } else if (dropped > 0) {
            note = "읽지 못한 칸이 " + dropped + "개 있어요. 확인하고 고쳐 주세요.";
        } else {
            note = "읽은 결과예요. 맞는지 확인한 뒤 저장해 주세요.";
        }
        return new Draft(List.copyOf(out), dropped, note);
    }

    /**
     * 그림 + 눈금 보정이 다 될 때만 칸을 잰다. 하나라도 안 되면 NONE(모델 시각 그대로).
     * 정각 가로줄로 맞추지 못한 그림(gridAligned=false)은 눈금 글자 위치가 앱마다 달라 통째로 밀릴 수 있다.
     * 그때는 픽셀로 잰 길이·간격은 믿고, 전체 위치만 "모델 시작 시각 − 잰 시작 시각"의 중앙값만큼 옮긴다(모델+픽셀 함께 쓰기).
     * 그 차이가 45분을 넘으면 어느 쪽이 맞는지 모르니 재지 않는다.
     */
    private record Measurer(TimetableGeometry geometry, TimetableGeometry.Calibration calibration) {

        static final Measurer NONE = new Measurer(null, null);
        /** 같은 칸에서 잰 시각과 모델 시각이 이보다 멀면 다른 칸을 잰 것으로 보고 모델 시각을 쓴다 */
        static final int MAX_DISAGREE_MINUTES = 150;

        static Measurer of(byte[] image, JsonNode hours, JsonNode classes) {
            if (image == null || !hours.isArray()) {
                return NONE;
            }
            List<TimetableGeometry.HourLabel> labels = new ArrayList<>();
            for (JsonNode h : hours) {
                JsonNode hour = h.path("hour");
                Optional<TimetableGeometry.Box> box = box(h.path("box_2d"));
                if (hour.isNumber() && box.isPresent()) {
                    labels.add(new TimetableGeometry.HourLabel(hour.asInt(), box.get()));
                }
            }
            Optional<TimetableGeometry> geometry = TimetableGeometry.of(image);
            if (geometry.isEmpty()) {
                return NONE;
            }
            Optional<TimetableGeometry.Calibration> cal = geometry.get().calibrate(labels);
            if (cal.isEmpty()) {
                return NONE;
            }
            Measurer m = new Measurer(geometry.get(), cal.get());
            return cal.get().gridAligned() ? m : m.alignedToModel(classes);
        }

        /** 잰 시작과 모델 시작의 차이(시간) 중앙값만큼 절편을 옮긴다. 45분 넘게 다르거나 비교할 칸이 없으면 NONE */
        private Measurer alignedToModel(JsonNode classes) {
            List<Double> diffs = new ArrayList<>();
            if (classes.isArray()) {
                for (JsonNode c : classes) {
                    LocalTime model = time(c.path("startTime").asString(""));
                    Optional<double[]> edges = box(c.path("box_2d")).flatMap(b -> geometry.measure(b, calibration));
                    if (model != null && edges.isPresent()) {
                        diffs.add(model.toSecondOfDay() / 3600.0 - calibration.hourAt(edges.get()[0]));
                    }
                }
            }
            if (diffs.isEmpty()) {
                return NONE;
            }
            List<Double> sorted = diffs.stream().sorted().toList();
            int n = sorted.size();
            double shift = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
            shift = Math.round(shift * 2) / 2.0;  // 눈금 글자 위치 차이는 0, 30분 단위로 생긴다
            if (Math.abs(shift) > 0.75) {
                return NONE;
            }
            double slope = calibration.slope();
            return new Measurer(geometry, new TimetableGeometry.Calibration(calibration.intercept() - shift * slope, slope, false));
        }

        /** {시작, 끝}. 잰 길이가 10분 미만이거나 모델 시각과 너무 멀면 빈 값 */
        Optional<LocalTime[]> times(JsonNode boxNode, LocalTime modelStart) {
            if (geometry == null) {
                return Optional.empty();
            }
            return box(boxNode).flatMap(b -> geometry.measure(b, calibration)).flatMap(edges -> {
                Optional<LocalTime> start = TimetableGeometry.timeAt(edges[0], calibration);
                Optional<LocalTime> end = TimetableGeometry.timeAt(edges[1], calibration);
                if (start.isEmpty() || end.isEmpty() || end.get().isBefore(start.get().plusMinutes(10))) {
                    return Optional.empty();
                }
                if (modelStart != null && Math.abs(start.get().toSecondOfDay() - modelStart.toSecondOfDay()) > MAX_DISAGREE_MINUTES * 60) {
                    return Optional.empty();
                }
                return Optional.of(new LocalTime[] {start.get(), end.get()});
            });
        }

        private static Optional<TimetableGeometry.Box> box(JsonNode n) {
            if (!n.isArray() || n.size() != 4) {
                return Optional.empty();
            }
            double[] v = new double[4];
            for (int i = 0; i < 4; i++) {
                if (!n.get(i).isNumber()) {
                    return Optional.empty();
                }
                v[i] = n.get(i).asDouble();
            }
            return TimetableGeometry.Box.of(v);
        }
    }

    private static ClassDraft normalize(JsonNode c, Measurer measurer) {
        if (!c.isObject()) {
            return null;
        }
        String day = c.path("dayOfWeek").asString("").trim().toUpperCase(Locale.ROOT);
        LocalTime start = time(c.path("startTime").asString(""));
        LocalTime end = time(c.path("endTime").asString(""));
        Optional<LocalTime[]> measured = measurer.times(c.path("box_2d"), start);
        if (measured.isPresent()) {
            start = measured.get()[0];
            end = measured.get()[1];
        }
        String name = c.path("name").asString("").trim().replaceAll("\\s+", " ");
        if (!DAYS.contains(day) || start == null || end == null || !end.isAfter(start) || name.isEmpty()) {
            return null;
        }
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME);
        }
        return new ClassDraft(day, start.format(HHMM), end.format(HHMM), name);
    }

    private static LocalTime time(String s) {
        String t = s.trim();
        if (t.matches("\\d:\\d{2}")) {
            t = "0" + t;
        }
        try {
            return LocalTime.parse(t, HHMM);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 모델이 ```json … ``` 으로 감싸면 벗긴다 */
    static String stripFence(String text) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl < 0 ? "" : t.substring(nl + 1);
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }

    /** 화면이 확인 카드로 보여 주는 초안. PUT /api/agent/schedule 본문과 같은 모양의 classes */
    public record Draft(List<ClassDraft> classes, int dropped, String note) {
    }

    public record ClassDraft(String dayOfWeek, String startTime, String endTime, String name) {
    }

    public static class InvalidImage extends RuntimeException {
        public InvalidImage(String message) {
            super(message);
        }
    }
}
