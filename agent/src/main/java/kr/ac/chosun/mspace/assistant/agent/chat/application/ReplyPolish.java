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

    /** 답 끝에 붙은 꼬리말 (여러 개가 이어져도 전부). 앞 줄바꿈·공백까지 함께 지운다 */
    static final Pattern FOOTER = Pattern.compile(
            "\\s*\\(\\s*(?:예약 시스템 \\d{1,2}:\\d{2} 조회(?: 기준| · 입력한 시간표 기준)?|입력한 시간표 기준)\\s*\\)\\s*$");

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

    /** 위 표에 없는 snake_case 낱말(모델이 지어낸 도구 이름 등)은 이 말로. 날짜·시각·URL에는 밑줄이 없어 안전하다 */
    static final String UNKNOWN_WORD = "그 기능";

    /** 도구 이름 앞뒤의 백틱·따옴표도 같이 지운다: `room_status` → 지금 빈 방 */
    private static final Pattern TOOL_TOKEN = Pattern.compile("[`'\"]?(?<![\\w가-힣])([a-z]+(?:_[a-z]+)+)(?!\\w)[`'\"]?");

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
        Matcher m = TOOL_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String word = TOOL_WORDS.getOrDefault(m.group(1), UNKNOWN_WORD);
            m.appendReplacement(sb, Matcher.quoteReplacement(word));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 둘 다: 꼬리말 지우고 도구 이름 바꾸기 */
    static String polish(String text) {
        return hideToolNames(stripFooters(text));
    }
}
