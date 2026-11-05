package kr.ac.chosun.mspace.assistant.mcp.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ContinueProperties;
import kr.ac.chosun.mspace.assistant.mcp.room.application.RoomsNow;
import kr.ac.chosun.mspace.assistant.mcp.room.application.RoomsNow.RoomNow;
import kr.ac.chosun.mspace.assistant.mcp.room.application.ShowRoomStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** latestEndAt 계산 (core ReservationTime.instant와 같은 규칙) */
class ShowRoomStatusTest {

    static final UserCredential ME = new UserCredential(Map.of("X-User-Id", "1"));
    /** 2026-11-03(화) 14:17 서울 */
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-03T14:17:00+09:00");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T05:17:00Z"), ZoneId.of("UTC"));

    static RoomNow room(int id, String status, OffsetDateTime nextStartAt) {
        return new RoomNow(id, id + "번 방", 3, 5, status, null, nextStartAt, null, null);
    }

    ShowRoomStatus status(RoomNow... rooms) {
        return new ShowRoomStatus(c -> new RoomsNow(NOW, 0, List.of(rooms), null), new ContinueProperties(10, 120, 30), CLOCK);
    }

    @Test
    @DisplayName("14:17에 비어 있는 방은 16:30까지(지금 + 2시간을 30분 칸으로 올림), 다음 예약이 15:00이면 15:00까지")
    void latestEnd() {
        // given
        RoomsNow shown = status(room(1, "AVAILABLE", null), room(2, "AVAILABLE", OffsetDateTime.parse("2026-11-03T15:00:00+09:00")),
                room(3, "IN_USE", null)).show(ME);

        // then
        assertThat(shown.rooms().get(0).latestEndAt()).isEqualTo(OffsetDateTime.parse("2026-11-03T16:30:00+09:00"));
        assertThat(shown.rooms().get(1).latestEndAt()).isEqualTo(OffsetDateTime.parse("2026-11-03T15:00:00+09:00"));
        assertThat(shown.rooms().get(2).latestEndAt()).isNull();
        assertThat(shown.availableCount()).isEqualTo(2);
        assertThat(shown.note()).contains("start_now_reservation");
    }

    @Test
    @DisplayName("다음 예약이 지금 이전이거나 같으면 바로 쓸 수 없는 방으로 센다")
    void nextTooClose() {
        RoomsNow shown = status(room(1, "AVAILABLE", NOW)).show(ME);

        assertThat(shown.rooms().get(0).latestEndAt()).isNull();
        assertThat(shown.availableCount()).isZero();
        assertThat(shown.note()).contains("지금 바로 쓸 수 있는 방이 없어요");
    }

    @Test
    @DisplayName("정각이면 올리지 않는다 (14:30 + 2시간 = 16:30)")
    void onSlotNotCeiled() {
        var exact = new ShowRoomStatus(c -> new RoomsNow(OffsetDateTime.parse("2026-11-03T14:30:00+09:00"), 0,
                List.of(room(1, "AVAILABLE", null)), null), new ContinueProperties(10, 120, 30), CLOCK);

        assertThat(exact.show(ME).rooms().get(0).latestEndAt()).isEqualTo(OffsetDateTime.parse("2026-11-03T16:30:00+09:00"));
    }
}
