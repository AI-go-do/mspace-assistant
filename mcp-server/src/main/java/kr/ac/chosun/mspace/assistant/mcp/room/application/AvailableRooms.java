package kr.ac.chosun.mspace.assistant.mcp.room.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * find_available_rooms 도구 출력. 48칸을 그대로 주지 않고 이어진 빈 구간으로 합친다
 * (방 5개 × 48칸이면 모델 토큰을 많이 쓰고, 모델이 칸을 잇다가 틀리기 쉬워서).
 *
 * @param note 모델이 학생에게 함께 전할 문장. 없으면 null
 */
public record AvailableRooms(LocalDate date, List<FreeRoom> rooms, String note) {

    public record FreeRoom(int roomId, String roomName, int minPeople, int maxPeople, List<FreeRange> free) {
    }

    public record FreeRange(OffsetDateTime startAt, OffsetDateTime endAt) {
    }
}
