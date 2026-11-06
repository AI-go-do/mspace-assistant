package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 모델 답을 화면에 내보내기 전에 다듬는다 (#74).
 * <ul>
 *   <li>모델이 흉내 내 직접 쓴 "(예약 시스템 HH:MM 조회 기준)" 꼬리말을 지운다. 꼬리말은 {@link AgentLoop#grounded}가
 *       코드로 한 번만 붙인다 (앞선 답의 꼬리말이 대화 맥락에 남아 모델이 따라 쓰는 일이 있었다)</li>
 *   <li>MCP 도구 이름(영문 snake_case)이 답에 그대로 나오면 학생이 읽을 말로 바꾼다. 지시문에도 쓰지 말라고
 *       적지만 모델은 가끔 어기므로 코드가 안전망이 된다</li>
 * </ul>
 */
final class ReplyPolish {

    private ReplyPolish() {
    }

    /** 답 끝에 붙은 꼬리말 (여러 개가 이어져도 전부). 앞 줄바꿈·공백, 닫는 괄호 뒤 마침표까지 함께 지운다 */
    static final Pattern FOOTER = Pattern.compile(
            "\\s*\\(\\s*(?:예약 시스템 \\d{1,2}:\\d{2} 조회(?: 기준| · 입력한 시간표 기준)?|입력한 시간표 기준)\\s*\\)[.。]?\\s*$", Pattern.CANON_EQ);

    /** 도구 이름 → 학생용 말. 순서는 긴 이름부터 (start_now_reservation이 reservation에 먹히지 않게 단어 경계도 본다) */
    static final Map<String, String> TOOL_WORDS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("start_now_reservation", "지금 바로 쓰기");
        m.put("find_available_rooms", "빈방 찾기");
        m.put("checkout_reservation", "퇴실");
        m.put("continue_reservation", "연장");
        m.put("create_reservation", "예약");
        m.put("cancel_reservation", "예약 취소");
        m.put("my_reservations", "내 예약");
        m.put("show_access_code", "입실 번호 보기");
        m.put("room_status", "지금 빈 방");
        m.put("usage_rules", "이용 규칙");
        m.put("my_schedule", "내 시간표");
        m.put("week_plan", "주간 계획");
        m.put("briefing", "오늘 브리핑");
        TOOL_WORDS = Map.copyOf(m);
    }

    /** 위 표에 없는 snake_case 낱말(모델이 지어낸 도구 이름 등)은 이 말로. URL·메일 주소 안은 건드리지 않는다 */
    static final String UNKNOWN_WORD = "그 기능";

    /** 도구 이름 앞뒤의 백틱·따옴표도 같이 지운다: `room_status` → 지금 빈 방 */
    private static final Pattern TOOL_TOKEN = Pattern.compile("[`'\"]?(?<![\\w가-힣/.@])([a-z]++(?:_[a-z]++)++)(?![\\w/@])[`'\"]?");

    /** URL·메일 주소. 이 안의 snake_case(경로 reset_password 등)는 바꾸면 링크가 깨진다 */
    private static final Pattern ADDRESS = Pattern.compile("(?:https?://|www\\.)\\S++|[\\w.+-]++@[\\w-]++(?:\\.[\\w-]++)++");

    /** 바꾼 말 바로 뒤에 붙은 조사. 받침에 맞게 고친다: 지금 바로 쓰기+을 → 지금 바로 쓰기를 */
    private static final Pattern PARTICLE = Pattern.compile("^(으로|로|을|를|이|가|은|는|과|와)", Pattern.CANON_EQ);

    /** 모델이 직접 쓴 꼬리말을 끝에서 모두 지운다. 없으면 그대로 */
    static String stripFooters(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        while (true) {
            Matcher m = FOOTER.matcher(out);
            if (!m.find()) {
                return out;
            }
            out = out.substring(0, m.start());
        }
    }

    /** 도구 이름을 학생용 말로 바꾼다. 없으면 그대로 */
    static String hideToolNames(String text) {
        if (text == null || text.indexOf('_') < 0) {
            return text;
        }
        java.util.List<int[]> addresses = new java.util.ArrayList<>();
        Matcher a = ADDRESS.matcher(text);
        while (a.find()) {
            addresses.add(new int[] {a.start(), a.end()});
        }
        Matcher m = TOOL_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            if (inside(addresses, m.start(1))) {
                continue;
            }
            String word = TOOL_WORDS.getOrDefault(m.group(1), UNKNOWN_WORD);
            sb.append(text, last, m.start()).append(word);
            last = m.end();
            Matcher p = PARTICLE.matcher(text.substring(last));
            if (p.find()) {
                String particle = p.group(1);
                sb.append(particle(word, particle));
                last += particle.length();
            }
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    private static boolean inside(java.util.List<int[]> ranges, int at) {
        for (int[] r : ranges) {
            if (at >= r[0] && at < r[1]) {
                return true;
            }
        }
        return false;
    }

    /** 앞말의 끝 글자 받침에 맞는 조사 (으로/로는 ㄹ 받침이면 로) */
    static String particle(String word, String particle) {
        char lastChar = word.charAt(word.length() - 1);
        if (lastChar < '가' || lastChar > '힣') {
            return particle;
        }
        int jong = (lastChar - '가') % 28; // 0이면 받침 없음, 8이면 ㄹ
        boolean batchim = jong != 0;
        return switch (particle) {
            case "을", "를" -> batchim ? "을" : "를";
            case "이", "가" -> batchim ? "이" : "가";
            case "은", "는" -> batchim ? "은" : "는";
            case "과", "와" -> batchim ? "과" : "와";
            case "으로", "로" -> batchim && jong != 8 ? "으로" : "로";
            default -> particle;
        };
    }

    /** 둘 다: 꼬리말 지우고 도구 이름 바꾸기 */
    static String polish(String text) {
        return hideToolNames(stripFooters(text));
    }
}
