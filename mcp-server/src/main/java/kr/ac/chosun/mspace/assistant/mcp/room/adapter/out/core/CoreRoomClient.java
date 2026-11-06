package kr.ac.chosun.mspace.assistant.mcp.room.adapter.out.core;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreHttp;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.room.application.RoomAvailabilityPort;
import kr.ac.chosun.mspace.assistant.mcp.room.application.RoomDay;
import org.springframework.stereotype.Component;

/** 코어 GET /api/rooms/availability?date= (core RoomAvailabilityController, FR-ROOM-001) */
@Component
class CoreRoomClient implements RoomAvailabilityPort {

    private final CoreHttp core;

    CoreRoomClient(CoreHttp core) {
        this.core = core;
    }

    @Override
    public RoomDay findDay(LocalDate date, UserCredential credential) {
        CoreDay body = core.call("GET /api/rooms/availability", credential,
                rest -> rest.get().uri(u -> u.path("/api/rooms/availability").queryParam("date", date).build()),
                CoreDay.class);
        if (body == null || body.rooms() == null) {
            return new RoomDay(date, List.of());
        }
        return new RoomDay(body.date(), body.rooms().stream().map(CoreRoom::toRoom).toList());
    }

    record CoreDay(LocalDate date, int slotMinutes, List<CoreRoom> rooms) {
    }

    record CoreRoom(int roomId, String roomName, int minPeople, int maxPeople, List<CoreSlot> slots) {

        RoomDay.RoomSlots toRoom() {
            List<RoomDay.Slot> converted = slots == null ? List.of() : slots.stream()
                    .map(s -> new RoomDay.Slot(SeoulTime.of(s.startAt()), SeoulTime.of(s.endAt()), s.available()))
                    .toList();
            return new RoomDay.RoomSlots(roomId, roomName, minPeople, maxPeople, converted);
        }
    }

    record CoreSlot(OffsetDateTime startAt, OffsetDateTime endAt, boolean available) {
    }
}
