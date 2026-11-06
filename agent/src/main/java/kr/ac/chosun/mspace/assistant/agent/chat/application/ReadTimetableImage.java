package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    static final String INSTRUCTION = """
            너는 대학생 시간표 캡처 화면을 읽어 JSON으로 옮기는 도우미다.
            JSON 외의 글은 쓰지 않는다. 형식:
            {"classes":[{"dayOfWeek":"MON","startTime":"09:00","endTime":"10:30","name":"과목명"}]}
            - dayOfWeek: MON TUE WED THU FRI SAT SUN 중 하나 (월=MON, 화=TUE, 수=WED, 목=THU, 금=FRI, 토=SAT, 일=SUN)
            - startTime, endTime: 24시간 HH:mm. 칸의 위아래 경계를 눈금에 맞춰 읽는다
            - name: 과목명만. 교수 이름, 강의실, 학번, 사람 이름은 절대 적지 않는다
            - 같은 과목이 여러 요일에 있으면 요일마다 한 항목씩
            - 읽을 수 없으면 {"classes":[]}
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
        return parse(text);
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

    Draft parse(String text) {
        JsonNode root;
        try {
            root = json.readTree(stripFence(text));
        } catch (JacksonException e) {
            return new Draft(List.of(), 0, "시간표를 읽지 못했어요. 더 선명한 사진으로 다시 올리거나 직접 입력해 주세요.");
        }
        JsonNode classes = root.path("classes");
        List<ClassDraft> out = new ArrayList<>();
        int dropped = 0;
        if (classes.isArray()) {
            for (JsonNode c : classes) {
                ClassDraft d = normalize(c);
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

    private static ClassDraft normalize(JsonNode c) {
        if (!c.isObject()) {
            return null;
        }
        String day = c.path("dayOfWeek").asString("").trim().toUpperCase(Locale.ROOT);
        LocalTime start = time(c.path("startTime").asString(""));
        LocalTime end = time(c.path("endTime").asString(""));
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
