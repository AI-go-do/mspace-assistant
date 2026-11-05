package kr.ac.chosun.mspace.assistant.mcp.rule.application;

import java.util.List;

/**
 * 이용 규칙 (FR-WEB-003). core GET /api/rules 모양 그대로 (core #57, docs #37).
 * 숫자는 core가 실제 검사에 쓰는 설정값이라 비서가 따로 외워 두지 않는다.
 */
public record UsageRules(Reservation reservation, List<Conduct> conduct, Reminders reminders) {

    /**
     * @param bookingWindowDays        오늘 포함 며칠 안에 시작하는 예약만
     * @param slotMinutes              예약 단위(분)
     * @param maxMinutes               1회 최대(분)
     * @param checkinOpenBeforeMinutes 시작 몇 분 전부터 입실 번호가 맞나
     * @param noShowMinutes            미리 예약 입실 기한(분)
     * @param instantNoShowMinutes     지금 바로 쓰기 입실 기한(분)
     */
    public record Reservation(int bookingWindowDays, int slotMinutes, int maxMinutes, int checkinOpenBeforeMinutes,
                              int noShowMinutes, int instantNoShowMinutes, Extension extension) {
    }

    /** 연장: 횟수, 종료 몇 분 전부터, 한 번에 최대(분), 이어진 사용으로 보는 간격(분) */
    public record Extension(int maxCount, int openBeforeMinutes, int maxMinutes, int chainGapMinutes) {
    }

    /** 이용 수칙 한 줄: 구분(key), 제목, 수칙, 어기면 */
    public record Conduct(String key, String title, String rule, String penalty) {
    }

    /** 체크인 직후·퇴실 확인 때 한 줄 안내 */
    public record Reminders(String checkin, String checkout) {
    }
}
