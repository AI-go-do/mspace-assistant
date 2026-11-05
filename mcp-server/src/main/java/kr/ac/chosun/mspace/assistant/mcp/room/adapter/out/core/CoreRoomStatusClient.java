package kr.ac.chosun.mspace.assistant.mcp.room.adapter.out.core;

import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreHttp;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.room.application.RoomStatusPort;
import kr.ac.chosun.mspace.assistant.mcp.room.application.RoomsNow;
import org.springframework.stereotype.Component;

/** 코어 GET /api/rooms/status (core RoomStatusController, FR-DEV-102, core #62) */
@Component
class CoreRoomStatusClient implements RoomStatusPort {

    private final CoreHttp core;

    CoreRoomStatusClient(CoreHttp core) {
        this.core = core;
    }

    @Override
    public RoomsNow status(UserCredential credential) {
        CoreStatus body = core.call("GET /api/rooms/status", credential,
                rest -> rest.get().uri("/api/rooms/status"), CoreStatus.class);
        if (body == null || body.rooms() == null) {
            // 2xx인데 본문이 비면 조회 실패다. "빈 방 없음"으로 안내하지 않는다 (usage_rules와 같은 규칙)
            throw new CoreApiException(CoreHttp.BAD_RESPONSE, "방 현황을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.", 502);
        }
        return new RoomsNow(body.now() == null ? null : SeoulTime.of(body.now()), body.availableCount(),
                body.rooms().stream().map(r -> new RoomsNow.RoomNow(r.roomId(), r.roomName(), r.minPeople(), r.maxPeople(),
                        r.status(), seoul(r.currentEndAt()), seoul(r.nextStartAt()), null, r.presence())).toList(), null);
    }

    private static OffsetDateTime seoul(OffsetDateTime t) {
        return t == null ? null : SeoulTime.of(t);
    }

    record CoreStatus(OffsetDateTime now, int availableCount, int roomCount, List<CoreRoom> rooms) {
    }

    record CoreRoom(int roomId, String roomName, int minPeople, int maxPeople, String status,
                    OffsetDateTime currentStartAt, OffsetDateTime currentEndAt, OffsetDateTime nextStartAt, String presence) {
    }
}
