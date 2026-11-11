package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 답 아래에 띄울 "다음 말" 버튼 글(빠른 답장). 모델이 아니라 마지막 도구 결과로 정한다(규칙).
 * 학생이 타이핑 없이 한 번 눌러 이어 가게 하는 시연용 장치. 버튼 글은 그대로 채팅으로 보낸다.
 * 확인 카드가 있으면 카드 버튼이 다음 행동이라 비워 둔다.
 */
final class QuickReplies {

    /** 도구 한 번의 결과. 인자는 안 본다 */
    record Seen(String tool, String json, boolean error) {
    }

    static final String MY_RESERVATIONS = "내 예약 보여 줘";
    static final String ROOMS_NOW = "지금 빈 방 있어?";
    static final String ACCESS_CODE = "입실 번호 보여 줘";

    static final List<String> START = List.of("내일 오후 2시 빈방 있어?", MY_RESERVATIONS, ROOMS_NOW);

    /** 비서가 인원을 물을 때(간단히 예약하기, FR-AGT-105). 방 인원 범위(1~4번 방 3~5명)에 맞춘 세 개 */
    static final List<String> HEADCOUNT = List.of("나 포함 3명", "나 포함 4명", "나 포함 5명");

    /** "몇 명이서", "몇 분이서" 같은 인원 질문. 물음표가 있어야 질문으로 본다("몇 명이 쓸 수 있는 방이 없어요"는 아님) */
    private static final String QUESTION_END = "[^?？\\n]{0,20}[?？]";
    /** 정규식 하나로 합치면 복잡도가 높아(Sonar S5843) 셋으로 나눈다. "몇 분 걸려?"(시간)는 잡지 않는다 */
    private static final List<java.util.regex.Pattern> ASKS_HEADCOUNT = List.of(
            java.util.regex.Pattern.compile("몇\\s*명" + QUESTION_END, java.util.regex.Pattern.CANON_EQ),
            java.util.regex.Pattern.compile("몇\\s*분\\s*이서" + QUESTION_END, java.util.regex.Pattern.CANON_EQ),
            java.util.regex.Pattern.compile("몇\\s*분\\s*(?:이|께서)\\s*(?:이용|쓰|사용|함께|오|가)" + QUESTION_END,
                    java.util.regex.Pattern.CANON_EQ));

    /** 비서가 몇 분 늘릴지 물을 때. 버튼 글만 보내도 뜻이 통하게 "연장해 줘"까지 넣는다 (최대 2시간·30분 단위) */
    static final List<String> EXTEND = List.of("30분 연장해 줘", "1시간 연장해 줘", "2시간 연장해 줘");

    /** 비서가 언제 쓸지 물을 때. 길이는 넣지 않는다(앞에서 말한 길이나 기본 2시간을 모델이 쓴다) */
    static final String AFTER_CLASS = "오늘 수업 끝나고 잡아 줘";
    static final List<String> WHEN = List.of("지금 바로 잡아 줘", AFTER_CLASS, "내일 오후 2시로 잡아 줘");

    /** 늘릴 길이를 묻는 질문: 질문 문장에 "늘리·연장"과 "몇 분·얼마·몇 시간"이 함께 있다 */
    private static final java.util.regex.Pattern EXTEND_VERB = java.util.regex.Pattern.compile("늘리|늘릴|연장");
    private static final java.util.regex.Pattern EXTEND_AMOUNT = java.util.regex.Pattern.compile("몇\\s*분|얼마|몇\\s*시간");
    /** 언제를 묻는 질문. "몇 시간"(길이)은 아니다 */
    private static final java.util.regex.Pattern ASKS_WHEN = java.util.regex.Pattern
            .compile("언제|며칠|날짜|시각|몇\\s*시(?!\\s*간)");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private QuickReplies() {
    }

    /** 답이 인원을 묻는 질문이면 true. 이때는 도구 결과 규칙 대신 {@link #HEADCOUNT} 버튼을 띄운다 */
    static boolean asksHeadcount(String reply) {
        return reply != null && ASKS_HEADCOUNT.stream().anyMatch(p -> p.matcher(reply).find());
    }

    /**
     * 비서 답의 마지막 질문에 맞춘 답 버튼(되묻기를 한 번 누름으로 끝내게). 인원 질문은 {@link #asksHeadcount}가 따로 본다.
     * 맞는 질문이 없으면 빈 목록. 시간표가 없다고 나온 대화(noSchedule)면 "수업 끝나고" 버튼은 뺀다(눌러도 시간표가 없음)
     */
    static List<String> forQuestion(String reply, boolean noSchedule) {
        String q = lastQuestion(reply);
        if (q == null) {
            return List.of();
        }
        if (EXTEND_VERB.matcher(q).find() && EXTEND_AMOUNT.matcher(q).find()) {
            return EXTEND;
        }
        if (ASKS_WHEN.matcher(q).find()) {
            return noSchedule ? WHEN.stream().filter(w -> !AFTER_CLASS.equals(w)).toList() : WHEN;
        }
        return List.of();
    }

    /**
     * 질문 버튼(asked)과 도구 결과 버튼(fromTools) 중 하나. 연장 길이 질문은 늘 질문 버튼.
     * "언제" 질문은 도구 결과 버튼에 "~잡아 줘"(빈방 대안 "15:00 2번 방 잡아 줘"처럼 시각까지 정해진 예약 말)가 있으면
     * 그쪽이 더 구체적이라 도구 결과 버튼. 없으면(내 예약 조회 뒤 등) 질문 버튼
     */
    static List<String> pick(List<String> asked, List<String> fromTools) {
        if (asked.isEmpty()) {
            return fromTools;
        }
        if (EXTEND.equals(asked) || fromTools.stream().noneMatch(t -> t.endsWith("잡아 줘"))) {
            return asked;
        }
        return fromTools;
    }

    /** 답에서 마지막 물음표로 끝나는 문장. 물음표가 없으면 null */
    static String lastQuestion(String reply) {
        if (reply == null) {
            return null;
        }
        int end = Math.max(reply.lastIndexOf('?'), reply.lastIndexOf('？'));
        if (end < 0) {
            return null;
        }
        int start = 0;
        for (int i = end - 1; i >= 0; i--) {
            char c = reply.charAt(i);
            if (c == '.' || c == '!' || c == '\n' || c == '?' || c == '？') {
                start = i + 1;
                break;
            }
        }
        return reply.substring(start, end + 1);
    }

    /**
     * @param seen       이번 대화에서 부른 도구(부른 순서)
     * @param hasCards   확인 카드나 입실 번호 카드가 있으면 true (카드가 다음 행동이라 버튼을 안 띄움)
     */
    static List<String> suggest(List<Seen> seen, boolean hasCards) {
        return suggest(seen, hasCards, null);
    }

    /**
     * @param lastAllowed 조회할 수 있는 마지막 날(오늘 + 미리 예약 일수 - 1). 다음 날이 이 날을 넘으면
     *                    "같은 시간 봐 줘"를 안 띄운다(눌러도 범위 밖이라 실패). null이면 제한 없음
     */
    static List<String> suggest(List<Seen> seen, boolean hasCards, java.time.LocalDate lastAllowed) {
        if (hasCards) {
            return List.of();
        }
        Seen last = null;
        for (Seen s : seen) {
            if (!s.error()) {
                last = s;
            }
        }
        if (last == null) {
            return seen.isEmpty() ? START : List.of();
        }
        JsonNode r;
        try {
            r = JSON.readTree(last.json());
        } catch (RuntimeException e) {
            return List.of();
        }
        if (r == null || r.isMissingNode() || r.isNull()) { // 빈 결과("")는 Jackson 3에서 null
            return List.of();
        }
        return switch (last.tool()) {
            case "find_available_rooms" -> r.path("rooms").isEmpty()
                    ? alternativesOr(r, lastAllowed)
                    : List.of("첫 번째로 나온 방 잡아 줘", "다른 시간도 볼게");
            case "my_reservations" -> reservations(r);
            case "week_plan" -> weekPlan(r);
            case "room_status" -> r.path("availableCount").asInt(0) > 0
                    ? List.of("지금 바로 1시간 쓸게", MY_RESERVATIONS)
                    : List.of("오늘 저녁 빈방 봐 줘", MY_RESERVATIONS);
            case "my_schedule" -> schedule(r);
            default -> List.of();
        };
    }

    private static List<String> schedule(JsonNode r) {
        if ("NO_SCHEDULE".equals(r.path("code").asString(null))) {
            return List.of(); // 화면이 [내 시간표 넣기] 버튼을 그린다 (hints)
        }
        return r.path("classes").isEmpty() ? List.of("오후 2시부터 빈방 봐 줘") : List.of("수업 끝나고 2시간 잡아 줘");
    }

    /** mcp-server FindAvailableRooms.HEADCOUNT_NOTE의 앞부분. 인원 때문에 비면 날짜를 바꿔도 같은 결과라 인원을 고르게 한다 */
    static final String HEADCOUNT_MARK = "명이 쓸 수 있는 방이 없어요";

    /** 서버가 고른 가까운 대안(alternatives)이 있으면 앞의 두 개를 "15:00 2번 방 잡아 줘"로. 없으면 날짜·인원 버튼 */
    private static List<String> alternativesOr(JsonNode r, java.time.LocalDate lastAllowed) {
        List<String> picks = new java.util.ArrayList<>();
        for (JsonNode a : r.path("alternatives")) {
            String start = a.path("startAt").asString("");
            String room = a.path("roomName").asString("");
            if (start.length() >= 16 && !room.isBlank()) {
                picks.add(start.substring(11, 16) + " " + room + " 잡아 줘");
            }
            if (picks.size() == 2) {
                break;
            }
        }
        if (!picks.isEmpty()) {
            picks.add("다른 시간도 볼게");
            return List.copyOf(picks);
        }
        return noRooms(r.path("date").asString(null), r.path("note").asString(""), lastAllowed);
    }

    private static List<String> noRooms(String date, String note, java.time.LocalDate lastAllowed) {
        if (note.contains(HEADCOUNT_MARK)) {
            return List.of("인원 바꿔서 다시 볼게", ROOMS_NOW);
        }
        if (lastAllowed != null && !nextDayAllowed(date, lastAllowed)) {
            return List.of(ROOMS_NOW);
        }
        return List.of(nextDay(date) + " 같은 시간 봐 줘", ROOMS_NOW);
    }

    /** 다음 날이 조회 범위 안인지. 날짜를 모르면 "다음 날"이 어디인지 몰라 안 띄운다 */
    private static boolean nextDayAllowed(String date, java.time.LocalDate lastAllowed) {
        try {
            return !java.time.LocalDate.parse(date).plusDays(1).isAfter(lastAllowed);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 조회한 날의 다음 날을 "11월 7일"로. 날짜를 모르면 "다음 날" (같은 날을 다시 조회하지 않게) */
    static String nextDay(String date) {
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(date).plusDays(1);
            return d.getMonthValue() + "월 " + d.getDayOfMonth() + "일";
        } catch (RuntimeException e) {
            return "다음 날";
        }
    }

    /** 주간 계획: 첫 번째로 쓸 수 있는 날을 "11월 7일 15:30 2번 방 잡아 줘"로. 없으면 길이를 줄이게 */
    private static List<String> weekPlan(JsonNode r) {
        for (JsonNode d : r.path("days")) {
            JsonNode o = d.path("option");
            String start = o.path("startAt").asString("");
            if (start.length() >= 16) {
                return List.of(nextDay(java.time.LocalDate.parse(start.substring(0, 10)).minusDays(1).toString())
                        + " " + start.substring(11, 16) + " " + o.path("roomName").asString("") + " 잡아 줘", "다른 날도 볼게");
            }
        }
        return List.of("1시간으로 다시 볼게", MY_RESERVATIONS);
    }

    /** 끝나지 않은 예약이 없고 최근에 끝낸 예약이 있으면 "지난번처럼" (모델이 방·시각을 그 예약에서 가져온다) */
    private static List<String> reservations(JsonNode r) {
        boolean inUse = false;
        boolean booked = false;
        boolean finished = false;
        for (JsonNode x : r.path("reservations")) {
            String st = x.path("status").asString("");
            inUse |= "IN_USE".equals(st);
            booked |= "BOOKED".equals(st);
            finished |= "CHECKED_OUT".equals(st) || "AUTO_CHECKED_OUT".equals(st);
        }
        if (inUse) {
            return List.of("30분 연장해 줘", "퇴실할게");
        }
        if (booked) {
            return List.of(ACCESS_CODE, "이 예약 취소해 줘");
        }
        if (finished) {
            return List.of("지난번처럼 다시 잡아 줘", ROOMS_NOW);
        }
        return List.of("내일 오후 2시 빈방 있어?", ROOMS_NOW);
    }
}
