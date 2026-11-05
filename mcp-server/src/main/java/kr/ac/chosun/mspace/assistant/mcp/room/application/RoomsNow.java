package kr.ac.chosun.mspace.assistant.mcp.room.application;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 지금 방 현황 (room_status 도구 결과).
 *
 * @param now            코어가 본 지금 시각(서울)
 * @param availableCount 지금 바로 잡을 수 있는 방 수
 * @param rooms          방마다 상태
 * @param note           모델이 학생에게 그대로 전할 문장 (예: 비어 있는 방이 없을 때)
 */
public record RoomsNow(OffsetDateTime now, int availableCount, List<RoomNow> rooms, String note) {

    /**
     * @param status        AVAILABLE(바로 잡을 수 있음) | RESERVED(예약 시간이지만 아직 입실 전) | IN_USE(사용 중)
     * @param currentEndAt  지금 걸린 예약이 끝나는 시각(언제 비는지). 비어 있으면 null
     * @param nextStartAt   지금 이후 가장 가까운 예약 시작. 없으면 null. 바로 쓰기는 이 시각 전까지만 된다
     * @param latestEndAt   지금 바로 쓰기를 시작하면 고를 수 있는 가장 늦은 끝 시각 (30분 칸, 최대 2시간, 다음 예약 전). AVAILABLE일 때만
     * @param presence      센서가 본 재실: OCCUPIED | EMPTY | UNKNOWN. 없으면 null
     */
    public record RoomNow(int roomId, String roomName, int minPeople, int maxPeople, String status,
                          OffsetDateTime currentEndAt, OffsetDateTime nextStartAt, OffsetDateTime latestEndAt,
                          String presence) {
    }
}
