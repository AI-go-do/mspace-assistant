package kr.ac.chosun.mspace.assistant.mcp.room.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 코어가 준 하루치 방별 30분 칸 (GET /api/rooms/availability). 시각은 서울 시간으로 바꿔 둔 값 */
public record RoomDay(LocalDate date, List<RoomSlots> rooms) {

    public record RoomSlots(int roomId, String roomName, int minPeople, int maxPeople, List<Slot> slots) {
    }

    /** available=false는 지난 칸이거나 예약된 칸. 코어가 둘을 구분하지 않는다 */
    public record Slot(OffsetDateTime startAt, OffsetDateTime endAt, boolean available) {
    }
}
