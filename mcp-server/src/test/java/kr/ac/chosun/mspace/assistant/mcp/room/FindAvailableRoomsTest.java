package kr.ac.chosun.mspace.assistant.mcp.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.room.application.AvailableRooms;
import kr.ac.chosun.mspace.assistant.mcp.room.application.AvailableRooms.FreeRange;
import kr.ac.chosun.mspace.assistant.mcp.room.application.FindAvailableRooms;
import kr.ac.chosun.mspace.assistant.mcp.room.application.RoomDay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 빈 구간 합치기와 시간·인원 고르기 (코어 없이) */
class FindAvailableRoomsTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    static final UserCredential USER = new UserCredential(Map.of("X-User-Id", "1"));

    /** 하루 48칸 중 busy(“HH:mm” 시작 칸)만 찬 방 */
    static RoomDay.RoomSlots room(int id, int min, int max, Set<String> busy) {
        List<RoomDay.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            OffsetDateTime s = DAY.atStartOfDay(SeoulTime.ZONE).plusMinutes(30L * i).toOffsetDateTime();
            slots.add(new RoomDay.Slot(s, s.plusMinutes(30), !busy.contains(s.toLocalTime().toString())));
        }
        return new RoomDay.RoomSlots(id, id + "번 방", min, max, slots);
    }

    static Set<String> busyBetween(String from, String to) {
        Set<String> busy = new java.util.HashSet<>();
        for (LocalTime t = LocalTime.parse(from); t.isBefore(LocalTime.parse(to)); t = t.plusMinutes(30)) {
            busy.add(t.toString());
        }
        return busy;
    }

    static FindAvailableRooms with(RoomDay.RoomSlots... rooms) {
        return new FindAvailableRooms((date, credential) -> new RoomDay(date, List.of(rooms)));
    }

    static OffsetDateTime at(String hhmm) {
        return DAY.atTime(LocalTime.parse(hhmm)).atZone(SeoulTime.ZONE).toOffsetDateTime();
    }

    @Test
    @DisplayName("이어진 빈 칸은 하나의 구간으로 합친다 (00:00–14:00, 16:00–24:00)")
    void mergesContiguousSlots() {
        // given: 14:00–16:00 예약됨
        var find = with(room(1, 3, 5, busyBetween("14:00", "16:00")));

        // when
        AvailableRooms result = find.find(DAY, null, null, null, USER);

        // then
        assertThat(result.rooms()).singleElement().satisfies(r -> assertThat(r.free()).containsExactly(
                new FreeRange(at("00:00"), at("14:00")),
                new FreeRange(at("16:00"), DAY.plusDays(1).atStartOfDay(SeoulTime.ZONE).toOffsetDateTime())));
        assertThat(result.note()).isNull();
    }

    @Test
    @DisplayName("from·to를 주면 그 시간 전체가 빈 방만 남긴다")
    void keepsRoomsFreeForWholeWindow() {
        // given: 1번 방은 15:00–16:00 참, 2번 방은 14:00–16:00 비어 있음
        var find = with(room(1, 3, 5, busyBetween("15:00", "16:00")), room(2, 3, 5, busyBetween("18:00", "19:00")));

        // when
        AvailableRooms result = find.find(DAY, LocalTime.of(14, 0), LocalTime.of(16, 0), null, USER);

        // then
        assertThat(result.rooms()).extracting(AvailableRooms.FreeRoom::roomId).containsExactly(2);
    }

    @Test
    @DisplayName("인원이 방 범위(min~max)를 벗어나면 그 방은 뺀다 (6명 → 5번 방만)")
    void filtersByHeadcount() {
        // given
        var find = with(room(1, 3, 5, Set.of()), room(5, 5, 8, Set.of()));

        // when
        AvailableRooms result = find.find(DAY, null, null, 6, USER);

        // then
        assertThat(result.rooms()).extracting(AvailableRooms.FreeRoom::roomId).containsExactly(5);
    }

    @Test
    @DisplayName("from만 주면 그 시각 이후에 끝나는 구간만 남긴다")
    void fromOnly() {
        // given
        var find = with(room(1, 3, 5, busyBetween("10:00", "12:00")));

        // when
        AvailableRooms result = find.find(DAY, LocalTime.of(13, 0), null, null, USER);

        // then
        assertThat(result.rooms().getFirst().free()).extracting(FreeRange::startAt).containsExactly(at("12:00"));
    }

    @Test
    @DisplayName("자정 넘김(23:00–01:00)은 시작 날짜의 23:00–24:00만 보고, 다음 날 부분은 예약 때 확인한다는 note")
    void crossesMidnight() {
        // given
        var find = with(room(1, 3, 5, Set.of()), room(2, 3, 5, Set.of("23:30")));

        // when
        AvailableRooms result = find.find(DAY, LocalTime.of(23, 0), LocalTime.of(1, 0), null, USER);

        // then
        assertThat(result.rooms()).extracting(AvailableRooms.FreeRoom::roomId).containsExactly(1);
        assertThat(result.note()).contains("자정 이후 부분은 예약할 때 확인돼요");
    }

    @Test
    @DisplayName("맞는 방이 없으면 빈 목록 + 다른 시간을 권하는 note")
    void none() {
        // given
        var find = with(room(1, 3, 5, busyBetween("00:00", "23:30")));

        // when
        AvailableRooms result = find.find(DAY, LocalTime.of(14, 0), LocalTime.of(16, 0), null, USER);

        // then
        assertThat(result.rooms()).isEmpty();
        assertThat(result.note()).contains("조건에 맞는 빈방이 없어요");
    }
}
