package kr.ac.chosun.mspace.assistant.mcp.reservation.adapter.in.mcp;

import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.Credentials;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.Proposal;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ProposeChange;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/** 쓰기 도구 cancel_reservation(FR-RSV-007), checkout_reservation(FR-RSV-008) 1단계. 확인안만 돌려준다 */
@Component
class ChangeReservationTools {

    private final ProposeChange propose;

    ChangeReservationTools(ProposeChange propose) {
        this.propose = propose;
    }

    @McpTool(name = "cancel_reservation",
            description = """
                    시작 전인 본인 예약(대표자, status BOOKED)의 취소를 제안한다. 이 도구는 취소하지 않는다.
                    확인안(summary)을 학생에게 보여 주고 화면에서 [확인]을 눌러 달라고 안내한다. "취소됐다"고 말하지 않는다.
                    reservationId는 my_reservations로 먼저 찾는다. 사용 중(IN_USE)인 예약은 checkout_reservation을 쓴다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false))
    Proposal cancelReservation(McpSyncRequestContext context,
                               @McpToolParam(description = "취소할 예약 id (my_reservations의 id)") Long reservationId) {
        return propose.cancel(Credentials.require(context), required(reservationId));
    }

    @McpTool(name = "checkout_reservation",
            description = """
                    사용 중인 본인 예약(대표자, status IN_USE)의 퇴실을 제안한다. 이 도구는 퇴실 처리하지 않는다.
                    확인안(summary, notes)을 학생에게 보여 주고 화면에서 [확인]을 눌러 달라고 안내한다.
                    끝나는 시각 전이면 조기 퇴실이라는 note가 붙는다. reservationId는 my_reservations로 먼저 찾는다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false))
    Proposal checkoutReservation(McpSyncRequestContext context,
                                 @McpToolParam(description = "퇴실할 예약 id (my_reservations의 id)") Long reservationId) {
        return propose.checkout(Credentials.require(context), required(reservationId));
    }

    private static long required(Long reservationId) {
        if (reservationId == null) {
            throw new CoreApiException("INVALID_REQUEST", "예약 id가 필요해요. my_reservations로 먼저 찾아 주세요.");
        }
        return reservationId;
    }
}
