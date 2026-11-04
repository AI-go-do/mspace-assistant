package kr.ac.chosun.mspace.assistant.mcp.reservation.application;

/**
 * 예약 상태 (TEAM.md 4장). core는 대문자 문자열로 주고, 어댑터가 여기로 바꾼다.
 * 모르는 값(core에 새 상태가 생김)은 UNKNOWN으로 두어 취소·퇴실 확인안을 만들지 않는다.
 */
public enum ReservationStatus {
    BOOKED, IN_USE, CHECKED_OUT, AUTO_CHECKED_OUT, CANCELED, NO_SHOW, UNKNOWN;

    public static ReservationStatus fromCore(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
