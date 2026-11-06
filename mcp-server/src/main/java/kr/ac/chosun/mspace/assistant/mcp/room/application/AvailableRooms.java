package kr.ac.chosun.mspace.assistant.mcp.room.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * find_available_rooms 도구 출력. 48칸을 그대로 주지 않고 이어진 빈 구간으로 합친다
 * (방 5개 × 48칸이면 모델 토큰을 많이 쓰고, 모델이 칸을 잇다가 틀리기 쉬워서).
 *
 * @param note         모델이 학생에게 함께 전할 문장. 없으면 null
 * @param alternatives 원하는 시간(from·to)에 빈방이 없을 때, 같은 날 같은 길이로 가장 가까운 시간 (최대 3개, 가까운 순).
 *                     모델이 free 구간을 보고 대안을 계산하다 틀리지 않게 서버가 골라 준다. 없으면 빈 목록
 */
public record AvailableRooms(LocalDate date, List<FreeRoom> rooms, String note, List<Alternative> alternatives) {

    public AvailableRooms(LocalDate date, List<FreeRoom> rooms, String note) {
        this(date, rooms, note, List.of());
    }

    /** 바로 예약 제안에 쓸 수 있는 한 칸: 방 + 시작·끝 */
    public record Alternative(int roomId, String roomName, OffsetDateTime startAt, OffsetDateTime endAt) {
    }

    public record FreeRoom(int roomId, String roomName, int minPeople, int maxPeople, List<FreeRange> free) {
    }

    public record FreeRange(OffsetDateTime startAt, OffsetDateTime endAt) {
    }
}
