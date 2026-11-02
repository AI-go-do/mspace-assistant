package kr.ac.chosun.mspace.assistant.mcp.reservation.adapter.in.mcp;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.Credentials;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.Proposal;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ProposeChange;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/**
 * 쓰기 도구 create_reservation 1단계 (FR-RSV-001). 예약을 만들지 않고 확인안만 돌려준다.
 * 참여자 학번은 입력에 없다: 학번이 외부 AI로 나가지 않게 확인 화면에서 받는다 (A-5).
 */
@Component
class CreateReservationTool {

    private final ProposeChange propose;

    CreateReservationTool(ProposeChange propose) {
        this.propose = propose;
    }

    @McpTool(name = "create_reservation",
            description = """
                    M-SPACE 방 예약을 제안한다. 이 도구는 예약을 만들지 않는다. 확인안(summary)과 확인 토큰을
                    돌려주고, 학생이 화면에서 [확인]을 누르고 함께 쓰는 사람의 학번을 넣어야 예약된다.
                    결과를 받으면 summary와 notes를 학생에게 그대로 보여 주고 확인을 눌러 달라고 안내한다.
                    "예약됐다"고 말하지 않는다. 학번은 묻지 않는다(확인 화면에서 받음).
                    먼저 find_available_rooms로 빈 시간을 확인한다. 시작·끝은 정각 또는 30분, 1회 최대 2시간,
                    시작이 오늘부터 7일 안이어야 한다. 학생 한 명은 끝나지 않은 예약을 1건만 가질 수 있다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false))
    Proposal createReservation(
            McpSyncRequestContext context,
            @McpToolParam(description = "방 번호 (find_available_rooms의 roomId)") Integer roomId,
            @McpToolParam(description = "시작 시각, 서울 시간 ISO-8601 (예: 2026-11-03T14:00:00+09:00)") String startAt,
            @McpToolParam(description = "끝 시각, 서울 시간 ISO-8601 (예: 2026-11-03T16:00:00+09:00)") String endAt,
            @McpToolParam(description = "사용할 사람 수, 예약하는 학생 본인 포함") Integer headcount) {
        if (roomId == null || headcount == null) {
            throw invalid();
        }
        return propose.create(Credentials.require(context), roomId, parse(startAt), parse(endAt), headcount);
    }

    private static OffsetDateTime parse(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw invalid();
        }
    }

    private static CoreApiException invalid() {
        return new CoreApiException("INVALID_REQUEST",
                "방 번호·인원과 시작·끝 시각(예: 2026-11-03T14:00:00+09:00)을 모두 넣어야 해요.");
    }
}
