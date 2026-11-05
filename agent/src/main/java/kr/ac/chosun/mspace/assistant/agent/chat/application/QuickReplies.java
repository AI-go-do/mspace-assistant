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

    static final List<String> START = List.of("내일 오후 2시 빈방 있어?", "내 예약 보여 줘", "지금 빈 방 있어?");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private QuickReplies() {
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
        return switch (last.tool()) {
            case "find_available_rooms" -> r.path("rooms").isEmpty()
                    ? noRooms(r.path("date").asString(null), lastAllowed)
                    : List.of("첫 번째로 나온 방 잡아 줘", "다른 시간도 볼게");
            case "my_reservations" -> reservations(r);
            case "room_status" -> r.path("availableCount").asInt(0) > 0
                    ? List.of("지금 바로 1시간 쓸게", "내 예약 보여 줘")
                    : List.of("오늘 저녁 빈방 봐 줘", "내 예약 보여 줘");
            case "my_schedule" -> "NO_SCHEDULE".equals(r.path("code").asString(null))
                    ? List.of() // 화면이 [내 시간표 넣기] 버튼을 그린다 (hints)
                    : r.path("classes").isEmpty() ? List.of("오후 2시부터 빈방 봐 줘") : List.of("수업 끝나고 2시간 잡아 줘");
            default -> List.of();
        };
    }

    private static List<String> noRooms(String date, java.time.LocalDate lastAllowed) {
        if (lastAllowed != null && !nextDayAllowed(date, lastAllowed)) {
            return List.of("지금 빈 방 있어?");
        }
        return List.of(nextDay(date) + " 같은 시간 봐 줘", "지금 빈 방 있어?");
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

    private static List<String> reservations(JsonNode r) {
        boolean inUse = false;
        boolean booked = false;
        for (JsonNode x : r.path("reservations")) {
            String st = x.path("status").asString("");
            inUse |= "IN_USE".equals(st);
            booked |= "BOOKED".equals(st);
        }
        if (inUse) {
            return List.of("30분 연장해 줘", "퇴실할게");
        }
        if (booked) {
            return List.of("입실 번호 보여 줘", "이 예약 취소해 줘");
        }
        return List.of("내일 오후 2시 빈방 있어?", "지금 빈 방 있어?");
    }
}
