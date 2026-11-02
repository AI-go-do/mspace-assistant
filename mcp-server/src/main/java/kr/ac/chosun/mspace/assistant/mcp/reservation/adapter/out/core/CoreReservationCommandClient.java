package kr.ac.chosun.mspace.assistant.mcp.reservation.adapter.out.core;

import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreHttp;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationCommandPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** 코어 POST /api/reservations (core ReservationController, FR-RSV-001) */
@Component
class CoreReservationCommandClient implements ReservationCommandPort {

    private final CoreHttp core;

    CoreReservationCommandClient(CoreHttp core) {
        this.core = core;
    }

    /**
     * 응답이 안 오면(타임아웃) 예약이 됐는지 모른다. 같은 멱등 키로 한 번만 다시 보낸다 (docs/설계.md 2.3):
     * 이미 만들어졌으면 core가 처음 예약을 200으로, 아니면 이번에 201로 만든다.
     */
    @Override
    public CreatedReservation create(UserCredential credential, int roomId, OffsetDateTime startAt,
                                     OffsetDateTime endAt, List<String> participantStudentIds, String idempotencyKey) {
        var body = new CreateRequest(roomId, startAt, endAt, participantStudentIds);
        try {
            return send(credential, body, idempotencyKey);
        } catch (CoreApiException first) {
            if (CoreHttp.BAD_RESPONSE.equals(first.code())) {
                throw unknown();
            }
            if (!CoreHttp.UNAVAILABLE.equals(first.code())) {
                throw first;
            }
            try {
                return send(credential, body, idempotencyKey);
            } catch (CoreApiException second) {
                if (CoreHttp.UNAVAILABLE.equals(second.code()) || CoreHttp.BAD_RESPONSE.equals(second.code())) {
                    throw unknown();
                }
                throw second;
            }
        }
    }

    private static CoreApiException unknown() {
        return new CoreApiException("RESERVATION_UNKNOWN", "예약이 됐는지 확인하지 못했어요. 내 예약에서 확인해 주세요.", 503);
    }

    private CreatedReservation send(UserCredential credential, CreateRequest body, String idempotencyKey) {
        ResponseEntity<CoreCreated> response = core.callEntity("POST /api/reservations", credential,
                rest -> rest.post().uri("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .body(body),
                CoreCreated.class);
        CoreCreated c = response.getBody();
        if (c == null || c.id() <= 0 || c.accessCode() == null || c.accessCode().isBlank()) {
            // 2xx인데 본문이 비었거나 id·입실 번호가 없음: 만들어졌을 수 있으니 '실패'가 아니라 '모름'으로 안내
            throw unknown();
        }
        return new CreatedReservation(c.id(), c.roomId(), SeoulTime.of(c.startAt()), SeoulTime.of(c.endAt()),
                c.status(), c.headcount(), c.accessCode(), response.getStatusCode().value() == 200);
    }

    record CreateRequest(int roomId, OffsetDateTime startAt, OffsetDateTime endAt,
                         List<String> participantStudentIds) {
    }

    /** core CreateReservationService.Created 모양 */
    record CoreCreated(long id, int roomId, OffsetDateTime startAt, OffsetDateTime endAt, String status,
                       int headcount, String accessCode) {
    }
}
