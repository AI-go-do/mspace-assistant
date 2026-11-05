package kr.ac.chosun.mspace.assistant.mcp.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationStatusTest {

    @Test
    @DisplayName("core 상태 문자열을 그대로 바꾸고, 모르는 값·null은 UNKNOWN")
    void fromCore() {
        assertThat(ReservationStatus.fromCore("IN_USE")).isEqualTo(ReservationStatus.IN_USE);
        assertThat(ReservationStatus.fromCore("PAUSED")).isEqualTo(ReservationStatus.UNKNOWN);
        assertThat(ReservationStatus.fromCore(null)).isEqualTo(ReservationStatus.UNKNOWN);
    }
}
