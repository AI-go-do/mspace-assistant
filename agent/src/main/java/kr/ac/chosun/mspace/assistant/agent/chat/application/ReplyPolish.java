package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 꼬리말 괄호 안의 글. 길이가 정해진 짧은 글에만 쓰도록 반복 수량자를 두지 않는다.
     * 예전에는 답 전체에 "\\s*(...)\\s*$"를 find로 돌려, 공백이 길게 이어지면 시간이 제곱으로 늘 수 있었다(Sonar S5852)
     */
    static final Pattern FOOTER_BODY = Pattern.compile(
            "예약 시스템 \\d{1,2}:\\d{2} 조회(?: 기준| · 입력한 시간표 기준)?|입력한 시간표 기준", Pattern.CANON_EQ);

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

    /** 바꾼 말 바로 뒤에 붙은 조사. 받침에 맞게 고친다: 지금 바로 쓰기+을 → 지금 바로 쓰기를 (긴 것부터: 으로가 로보다 먼저) */
    private static final List<String> PARTICLES = List.of("으로", "로", "을", "를", "이", "가", "은", "는", "과", "와");

    private static final List<String> URL_PREFIXES = List.of("http://", "https://", "www.");

    /** 모델이 직접 쓴 꼬리말을 끝에서 모두 지운다. 없으면 그대로 */
    static String stripFooters(String text) {
        if (text == null) {
            return null;
        }
        // 끝 위치만 줄여 가다 마지막에 한 번만 자른다 (꼬리말이 여러 개여도 문자열을 매번 복사하지 않게)
        int end = text.length();
        int cut;
        while ((cut = footerStart(text, end)) >= 0) {
            end = cut;
        }
        return end == text.length() ? text : text.substring(0, end);
    }

    /**
     * text[0, limit) 끝에 붙은 꼬리말 하나의 시작 위치(앞 공백 포함). 없으면 -1.
     * 뒤에서부터 한 번만 훑어 길이에 비례하는 시간만 쓴다: 공백 → [. 。] → ")" → 안쪽 글 → "(" → 앞 공백
     */
    static int footerStart(String text, int limit) {
        int end = skipSpaceBack(text, limit);
        if (end > 0 && (text.charAt(end - 1) == '.' || text.charAt(end - 1) == '。')) {
            end--;
        }
        if (end == 0 || text.charAt(end - 1) != ')') {
            return -1;
        }
        int open = text.lastIndexOf('(', end - 2);
        if (open < 0) {
            return -1;
        }
        String body = text.substring(open + 1, end - 1).strip();
        if (!FOOTER_BODY.matcher(body).matches()) {
            return -1;
        }
        return skipSpaceBack(text, open);
    }

    private static int skipSpaceBack(String text, int from) {
        int i = from;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /**
     * 도구 이름(영문 소문자 snake_case)을 학생용 말로 바꾼다. 없으면 그대로.
     * 앞뒤 백틱·따옴표도 같이 지운다: `room_status` → 지금 빈 방. URL·메일 주소 안은 건드리지 않는다(링크가 깨짐).
     * 정규식 대신 한 번 훑어서 찾는다: 긴 입력에서 되짚기(backtracking)로 느려질 일이 없게(Sonar S5852)
     */
    static String hideToolNames(String text) {
        if (text == null || text.indexOf('_') < 0) {
            return text;
        }
        List<int[]> addresses = addressRanges(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        int cursor = 0;
        int i = 0;
        while (i < text.length()) {
            if (!isSnakeChar(text.charAt(i))) {
                i++;
                continue;
            }
            int j = runEnd(text, i);
            // i는 앞으로만 가므로 이미 지난 주소 범위는 다시 보지 않는다(범위 검사가 입력 길이에 비례)
            while (cursor < addresses.size() && addresses.get(cursor)[1] <= i) {
                cursor++;
            }
            boolean inAddress = cursor < addresses.size() && addresses.get(cursor)[0] <= i;
            if (!inAddress && isToolToken(text, i, j)) {
                j = replaceToken(text, sb, last, i, j);
                last = j;
            }
            i = j;
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    private static int runEnd(String text, int from) {
        int j = from;
        while (j < text.length() && isSnakeChar(text.charAt(j))) {
            j++;
        }
        return j;
    }

    /** text[i, j)의 도구 이름(앞뒤 따옴표·뒤 조사 포함)을 학생 말로 바꿔 sb에 붙이고, 다음에 읽을 위치를 돌려준다 */
    private static int replaceToken(String text, StringBuilder sb, int last, int i, int j) {
        int from = i > last && isQuote(text.charAt(i - 1)) ? i - 1 : i;
        int to = j < text.length() && isQuote(text.charAt(j)) ? j + 1 : j;
        String word = TOOL_WORDS.getOrDefault(text.substring(i, j), UNKNOWN_WORD);
        sb.append(text, last, from).append(word);
        String particle = leadingParticle(text, to);
        if (particle == null) {
            return to;
        }
        sb.append(particle(word, particle));
        return to + particle.length();
    }

    /** [i, j)가 앞뒤로 낱말에 붙지 않은 a_b(_c…) 꼴인지. 밑줄로 시작·끝나거나 두 개 이어지면 아님 */
    private static boolean isToolToken(String text, int i, int j) {
        if (i > 0 && isWordBefore(text.charAt(i - 1))) {
            return false;
        }
        if (j < text.length() && isWordAfter(text.charAt(j))) {
            return false;
        }
        if (text.charAt(i) == '_' || text.charAt(j - 1) == '_') {
            return false;
        }
        boolean underscore = false;
        for (int k = i + 1; k < j; k++) {
            if (text.charAt(k) == '_') {
                if (text.charAt(k - 1) == '_') {
                    return false;
                }
                underscore = true;
            }
        }
        return underscore;
    }

    private static boolean isSnakeChar(char c) {
        return (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static boolean isWordBefore(char c) {
        return isAsciiWord(c) || (c >= '가' && c <= '힣') || c == '/' || c == '.' || c == '@';
    }

    private static boolean isWordAfter(char c) {
        return isAsciiWord(c) || c == '/' || c == '@';
    }

    private static boolean isQuote(char c) {
        return c == '`' || c == '\'' || c == '"';
    }

    private static String leadingParticle(String text, int at) {
        for (String p : PARTICLES) {
            if (text.startsWith(p, at)) {
                return p;
            }
        }
        return null;
    }

    /**
     * URL(http://, https://, www. 부터 공백 전까지)과 메일 주소(local@a.b) 위치. 이 안의 snake_case는 바꾸지 않는다.
     * 시작 위치로 정렬하고 겹치는 범위는 합쳐서 돌려준다(hideToolNames가 커서로 한 번만 훑게)
     */
    static List<int[]> addressRanges(String text) {
        List<int[]> ranges = new ArrayList<>();
        for (String prefix : URL_PREFIXES) {
            int from = text.indexOf(prefix);
            while (from >= 0) {
                int end = from + prefix.length();
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                    end++;
                }
                ranges.add(new int[] {from, end});
                from = text.indexOf(prefix, end);
            }
        }
        int at = text.indexOf('@');
        while (at >= 0) {
            int start = at;
            while (start > 0 && isMailLocal(text.charAt(start - 1))) {
                start--;
            }
            int end = mailDomainEnd(text, at + 1);
            if (start < at && end > 0) {
                ranges.add(new int[] {start, end});
            }
            at = text.indexOf('@', at + 1);
        }
        return merged(ranges);
    }

    private static List<int[]> merged(List<int[]> ranges) {
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> out = new ArrayList<>();
        for (int[] r : ranges) {
            int[] prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev != null && r[0] <= prev[1]) {
                prev[1] = Math.max(prev[1], r[1]);
            } else {
                out.add(new int[] {r[0], r[1]});
            }
        }
        return out;
    }

    private static boolean isMailLocal(char c) {
        return isAsciiWord(c) || c == '.' || c == '+' || c == '-';
    }

    private static boolean isLabelChar(char c) {
        return isAsciiWord(c) || c == '-';
    }

    /** 도메인(label(.label)+)이 끝나는 위치. 점으로 나뉜 조각이 둘 이상 아니면 -1 */
    private static int mailDomainEnd(String text, int from) {
        int labels = 0;
        int end = from;
        int i = from;
        boolean more = true;
        while (more) {
            int j = i;
            while (j < text.length() && isLabelChar(text.charAt(j))) {
                j++;
            }
            more = j > i;
            if (more) {
                labels++;
                end = j;
                more = j < text.length() && text.charAt(j) == '.';
                i = j + 1;
            }
        }
        return labels >= 2 ? end : -1;
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

    /**
     * "확인 카드를 띄웠어요", "카드를 바꿨어요", "확인 카드가 생성됐어요", "확인 카드 띄웠어요"처럼 이번 답에서 카드를 만들었다고 말하는 표현.
     * 조사(를·가·도)는 있어도 없어도 된다. "확인 카드에서 친구를 고르면 돼요" 같은 안내,
     * "입실 번호 카드를 띄웠어요"(쓰기 도구가 아닌 카드)는 아니다. 확인 카드는 쓰기 도구를 불러야만 생긴다
     */
    static final Pattern CARD_CLAIM = Pattern.compile(
            "(?<!번호 )(?<!번호)카드 ?(?:를|가|도)? ?(?:다시 |새로 )?"
                    + "(?:띄웠|띄워 ?드렸|바꿨|바꾸었|고쳤|만들었|생성했|수정했|변경했|보여 ?드렸|올렸|준비했"
                    + "|생성됐|생성되었|만들어졌|준비됐|준비되었|바뀌었|바꼈|수정됐|수정되었|변경됐|변경되었|떴|올라갔)");

    /** "[확인]을 눌러 주세요"처럼 지금 확인 버튼을 누르라는 말. "[확인]을 누르면 ~돼요" 같은 설명은 아니다 */
    static final Pattern PRESS_CONFIRM = Pattern.compile("\\[확인\\] ?(?:을|를)? ?(?:바로 |지금 )?눌러");

    /**
     * "카드가 나오면 [확인]을 눌러 주세요"처럼 확인 카드가 뜬 뒤를 말하는 조건 안내. 지금 카드가 있다는 말이 아니다.
     * 조건의 주어가 카드나 [확인]이어야 한다. "빈방이 보이면 [확인]을 눌러"는 지금 버튼이 있다는 말이라 해당하지 않는다
     */
    private static final Pattern WHEN_CARD_SHOWS = Pattern.compile(
            "(?:카드|\\[확인\\]|버튼) ?(?:이|가|도)? ?(?:화면에 )?(?:나오|뜨|보이|생기|올라오)면");

    /** 확인 카드를 가리키는 말. "확인카드"처럼 붙여 써도 된다 */
    private static final Pattern CONFIRM_CARD_WORD = Pattern.compile("확인 ?카드|\\[확인\\]");

    /**
     * 확인 카드가 없는데 있다고 말하는 문장인지. 카드를 만들었다는 말이나 [확인]을 누르라는 말이다.
     * 입실 번호 카드가 이미 화면에 있으면(otherCardShown) "카드를 띄웠어요"는 그 카드일 수 있으니,
     * 같은 문장에 "확인 카드"나 "[확인]"이 있을 때만 확인 카드 주장으로 본다
     */
    static boolean isConfirmClaim(String sentence, boolean otherCardShown) {
        if (PRESS_CONFIRM.matcher(sentence).find() && !WHEN_CARD_SHOWS.matcher(sentence).find()) {
            return true;
        }
        if (!CARD_CLAIM.matcher(sentence).find()) {
            return false;
        }
        return !otherCardShown || CONFIRM_CARD_WORD.matcher(sentence).find();
    }

    /** 답의 어느 문장이든 확인 카드가 있다고 말하면 true. 문장마다 따로 본다 */
    static boolean claimsConfirmCard(String text, boolean otherCardShown) {
        if (text == null) {
            return false;
        }
        for (String line : text.split("\\R")) {
            for (String sentence : sentences(line)) {
                if (isConfirmClaim(sentence, otherCardShown)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String[] sentences(String line) {
        return line.split("(?<=[.!?。])\\s+");
    }

    /** 확인 카드가 있다고 말하는 문장과 "[확인]" 문장을 지운다. "카드가 나오면 [확인]을…" 같은 조건 안내는 둔다. 다 지워지면 안내 한 줄 */
    static String dropCardClaims(String text, boolean otherCardShown) {
        if (text == null) {
            return null;
        }
        // 줄마다 따로, 줄 안에서는 문장마다 본다. 카드 주장 줄 때문에 다른 줄(빈방 안내 등)까지 지우지 않는다
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            StringBuilder kept = new StringBuilder();
            for (String sentence : sentences(line)) {
                if (isConfirmClaim(sentence, otherCardShown)
                        || (sentence.contains("[확인]") && !WHEN_CARD_SHOWS.matcher(sentence).find())) {
                    continue;
                }
                kept.append(kept.isEmpty() ? "" : " ").append(sentence);
            }
            if (!kept.toString().isBlank() || (line.isBlank() && !lines.isEmpty())) {
                lines.add(kept.toString());
            }
        }
        String out = String.join("\n", lines).strip();
        return out.isEmpty() ? NO_CARD_REPLY : out;
    }

    static final String NO_CARD_REPLY = "확인 카드를 만들지 못했어요. 날짜·시각·인원을 한 번 더 말해 주시면 다시 잡아 볼게요.";

    /** 둘 다: 꼬리말 지우고 도구 이름 바꾸기 */
    static String polish(String text) {
        return hideToolNames(stripFooters(text));
    }
}
