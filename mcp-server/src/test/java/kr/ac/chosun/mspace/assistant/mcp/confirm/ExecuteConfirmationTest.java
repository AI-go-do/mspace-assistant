package kr.ac.chosun.mspace.assistant.mcp.confirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.confirm.adapter.out.memory.InMemoryConfirmationRepository;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ConfirmProperties;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ConfirmationStore;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ExecuteConfirmation;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.PendingAction;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationCommandPort;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationCommandPort.CreatedReservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 즉시 시작 확인: 제안과 확인 사이에 끝 시각이 지나면 토큰을 쓰지 않고 돌려보낸다 (PR #65 리뷰) */
class ExecuteConfirmationTest {

    static final UserCredential ME = new UserCredential(Map.of("X-User-Id", "1"));
    /** 2026-11-03 15:59:30 서울 */
    static final Instant PROPOSED_AT = Instant.parse("2026-11-03T06:59:30Z");
    static final OffsetDateTime END = OffsetDateTime.parse("2026-11-03T16:00:00+09:00");

    static class MovableClock extends Clock {
        Instant now = PROPOSED_AT;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MovableClock clock = new MovableClock();
    final ConfirmationStore store = new ConfirmationStore(new InMemoryConfirmationRepository(), clock,
            new ConfirmProperties(Duration.ofMinutes(2)));
    final ReservationCommandPort core = mock(ReservationCommandPort.class);
    final ExecuteConfirmation execute = new ExecuteConfirmation(store, core, clock);

    @Test
    @DisplayName("15:59:30에 16:00 끝으로 제안 → 16:00:10에 확인하면 core를 부르지 않고 400, 토큰은 남는다")
    void endPassedBeforeConfirm() {
        // given
        String token = store.issue(ME, new PendingAction.StartNow(2, END, 1)).token();
        clock.now = Instant.parse("2026-11-03T07:00:10Z");

        // when / then
        assertThatThrownBy(() -> execute.execute(token, ME, List.of()))
                .isInstanceOf(CoreApiException.class)
                .hasMessageContaining("이미 지났어요")
                .extracting("code").isEqualTo("RESERVATION_INVALID_LENGTH");
        verify(core, never()).startNow(any(), anyInt(), any(), anyList(), anyString());
        assertThat(store.peek(token, ME)).isInstanceOf(PendingAction.StartNow.class);
    }

    @Test
    @DisplayName("끝 시각이 아직 뒤면 그대로 실행된다")
    void endStillAhead() {
        // given
        String token = store.issue(ME, new PendingAction.StartNow(2, END, 1)).token();
        when(core.startNow(any(), anyInt(), any(), anyList(), anyString()))
                .thenReturn(new CreatedReservation(9, 2, END.minusMinutes(1), END, "BOOKED", 1, "482913", false));

        // when
        var executed = execute.execute(token, ME, List.of());

        // then
        assertThat(executed.action()).isEqualTo("START_NOW");
        verify(core).startNow(ME, 2, END, List.of(), token);
    }
}
