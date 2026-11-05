package kr.ac.chosun.mspace.assistant.mcp.reservation.adapter.in.mcp;

import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.Credentials;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.Proposal;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ProposeChange;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/**
 * 쓰기 도구 start_now_reservation 1단계 (FR-RSV-103 즉시 시작). 확인안만 돌려주고, 시작 시각은 학생이 [확인]을 누를 때 core가 정한다.
 * 참여자 학번은 입력에 없다 (A-5).
 */
@Component
class StartNowReservationTool {

    private final ProposeChange propose;

    StartNowReservationTool(ProposeChange propose) {
        this.propose = propose;
    }

    @McpTool(name = "start_now_reservation",
            description = """
                    M-SPACE 방을 지금 바로 쓰기 시작하는 예약을 제안한다. "지금 2번 방 1시간 쓸래", "당장 들어갈게"처럼
                    지금부터 쓰고 싶을 때 쓴다. 날짜나 나중 시각을 말하면 create_reservation을 쓴다.
                    이 도구는 예약을 만들지 않는다. 확인안(summary)과 확인 토큰을 돌려주고, 학생이 화면에서 [확인]을 눌러야
                    그 시각부터 시작된다. 결과의 summary와 notes를 학생에게 그대로 보여 주고 확인을 눌러 달라고 안내한다.
                    "예약됐다"고 말하지 않는다. 학번은 묻지 않는다(확인 화면에서 받음).
                    먼저 room_status로 지금 비어 있는 방과 latestEndAt(고를 수 있는 가장 늦은 끝)을 확인한다.
                    끝 시각은 정각 또는 30분이고, 지금부터 최대 2시간(칸으로 올림) 안이어야 한다. 입실은 시작 10분 안에.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false))
    Proposal startNowReservation(
            McpSyncRequestContext context,
            @McpToolParam(description = "방 번호 (room_status의 roomId)") Integer roomId,
            @McpToolParam(description = "끝 시각, 서울 시간 ISO-8601 (예: 2026-11-03T16:30:00+09:00). 정각 또는 30분") String endAt,
            @McpToolParam(description = "사용할 사람 수, 예약하는 학생 본인 포함") Integer headcount) {
        if (roomId == null || headcount == null) {
            throw new CoreApiException("INVALID_REQUEST", "방 번호·인원과 끝 시각(예: 2026-11-03T16:30:00+09:00)을 모두 넣어야 해요.");
        }
        return propose.startNow(Credentials.require(context), roomId, CreateReservationTool.parse(endAt), headcount);
    }
}
