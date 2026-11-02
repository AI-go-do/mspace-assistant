package kr.ac.chosun.mspace.assistant.mcp.reservation.adapter.in.mcp;

import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.MyReservation;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationQueryPort;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/** 읽기 도구 my_reservations (FR-RSV-006). 설명 문장은 모델이 읽는다 */
@Component
class MyReservationsTool {

    private final ReservationQueryPort reservations;

    MyReservationsTool(ReservationQueryPort reservations) {
        this.reservations = reservations;
    }

    @McpTool(name = "my_reservations",
            description = """
                    로그인한 학생 본인의 M-SPACE 예약 목록을 가져온다. 대표자인 예약과 참여자인 예약이 모두 나온다.
                    "내 예약 보여 줘", "오늘 몇 시에 예약했지?"처럼 본인 예약을 물을 때, 그리고 취소·퇴실 전에
                    대상 예약 id를 찾을 때 쓴다. 입력은 없다.
                    끝나지 않은 예약이 위에 있다. owner가 true인 예약만 취소·퇴실할 수 있다.
                    시각은 서울 시간(+09:00)이다. status: BOOKED(예약됨), IN_USE(사용 중), CHECKED_OUT(퇴실),
                    AUTO_CHECKED_OUT(자동 퇴실), NO_SHOW(노쇼), CANCELED(취소).""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    MyReservationsResult myReservations(McpSyncRequestContext context) {
        UserCredential credential = UserCredential.from(context.transportContext().get(UserCredential.CONTEXT_KEY))
                .orElseThrow(() -> new CoreApiException("UNAUTHORIZED", "로그인이 필요합니다"));
        return new MyReservationsResult(reservations.findMine(credential));
    }

    /** 목록을 객체로 감싼다: MCP 구조화 결과(structuredContent)는 객체여야 해서 */
    record MyReservationsResult(List<MyReservation> reservations) {
    }
}
