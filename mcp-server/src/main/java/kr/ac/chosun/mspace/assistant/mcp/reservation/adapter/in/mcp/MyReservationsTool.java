package kr.ac.chosun.mspace.assistant.mcp.reservation.adapter.in.mcp;

import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.Credentials;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.MyReservation;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationQueryPort;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/** 읽기 도구 my_reservations (FR-RSV-006). 설명 문장은 모델이 읽는다 */
@Component
class MyReservationsTool {

    /** 끝난 예약은 끝난 지 이 날짜가 지나면 목록에서 뺀다 (지난 예약 번호 질문은 최근 것만 필요) */
    /** 끝난 예약을 며칠까지 보여 주나. "지난주처럼 잡아 줘"가 딱 7일 뒤에도 되도록 7일보다 넉넉히 (11/6) */
    static final int FINISHED_KEEP_DAYS = 14;

    private final ReservationQueryPort reservations;

    MyReservationsTool(ReservationQueryPort reservations) {
        this.reservations = reservations;
    }

    @McpTool(name = "my_reservations",
            description = """
                    로그인한 학생 본인의 M-SPACE 예약 목록을 가져온다. 대표자인 예약과 참여자인 예약이 모두 나온다.
                    "내 예약 보여 줘", "오늘 몇 시에 예약했지?"처럼 본인 예약을 물을 때, 그리고 취소·퇴실 전에
                    대상 예약 id를 찾을 때 쓴다. 입력은 없다.
                    끝나지 않은 예약과 끝난 지 14일 안 된 예약만 나온다. owner가 true인 예약만 취소·퇴실할 수 있다.
                    입실 번호는 이 도구로 알 수 없다. 대표자 본인의 입실 번호 요청은 별도 경로로 처리한다.
                    시각은 서울 시간(+09:00)이다. status: BOOKED(예약됨), IN_USE(사용 중), CHECKED_OUT(퇴실),
                    AUTO_CHECKED_OUT(자동 퇴실), NO_SHOW(노쇼), CANCELED(취소).""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    MyReservationsResult myReservations(McpSyncRequestContext context) {
        OffsetDateTime cutoff = OffsetDateTime.now(SeoulTime.ZONE).minusDays(FINISHED_KEEP_DAYS);
        return new MyReservationsResult(reservations.findMine(Credentials.require(context)).stream()
                .filter(r -> keepForModel(r, cutoff)).toList());
    }

    /** 끝나지 않은 예약(BOOKED·IN_USE·알 수 없음)은 항상 남긴다. 끝난 예약은 끝 시각이 기준 이후일 때만 */
    static boolean keepForModel(MyReservation r, OffsetDateTime cutoff) {
        return switch (r.status()) {
            case CHECKED_OUT, AUTO_CHECKED_OUT, CANCELED, NO_SHOW ->
                    r.endAt() != null && r.endAt().isAfter(cutoff);
            default -> true;
        };
    }

    /** 목록을 객체로 감싼다: MCP 구조화 결과(structuredContent)는 객체여야 해서 */
    record MyReservationsResult(List<MyReservation> reservations) {
    }
}
