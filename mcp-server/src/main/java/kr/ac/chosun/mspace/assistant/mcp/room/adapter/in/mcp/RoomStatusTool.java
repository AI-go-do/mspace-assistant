package kr.ac.chosun.mspace.assistant.mcp.room.adapter.in.mcp;

import kr.ac.chosun.mspace.assistant.mcp.common.Credentials;
import kr.ac.chosun.mspace.assistant.mcp.room.application.RoomsNow;
import kr.ac.chosun.mspace.assistant.mcp.room.application.ShowRoomStatus;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/** 읽기 도구 room_status (FR-DEV-102). "지금 바로 쓸 수 있는 방" 질문에 쓴다 */
@Component
class RoomStatusTool {

    private final ShowRoomStatus status;

    RoomStatusTool(ShowRoomStatus status) {
        this.status = status;
    }

    @McpTool(name = "room_status",
            description = """
                    M-SPACE 방(1~5번)의 지금 이 순간 현황이다. "지금 바로 쓸 수 있는 방", "지금 빈 방 있어?",
                    "당장 들어갈 방" 같은 질문에 쓴다. 날짜·시간을 말한 질문은 find_available_rooms를 쓴다.
                    status가 AVAILABLE이고 latestEndAt이 있는 방만 바로 쓸 수 있다. latestEndAt은 지금 바로 쓰기를
                    시작하면 고를 수 있는 가장 늦은 끝 시각이다(30분 칸, 최대 2시간, 다음 예약 전). 사용 중이거나
                    예약된 방은 currentEndAt(언제 비는지)을 알려 준다. note가 있으면 학생에게 그대로 전한다.
                    입력은 없다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    RoomsNow roomStatus(McpSyncRequestContext context) {
        return status.show(Credentials.require(context));
    }
}
