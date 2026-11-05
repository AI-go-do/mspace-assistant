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

    /**
     * 비서가 만든 예약 표시 (core #45, NFR-COM-008). core가 reservation.created_via = AGENT로 저장해
     * 웹·비서 예약 건수를 따로 센다. 통계용 표시라 권한과는 상관없다 (권한은 학생 본인 토큰으로 core가 본다)
     */
    static final String CREATED_VIA_HEADER = "X-Created-Via";
    static final String CREATED_VIA_AGENT = "AGENT";

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
            if (!CoreHttp.UNAVAILABLE.equals(first.code())) {
                throw unknownIfServerSide(first);
            }
            try {
                return send(credential, body, idempotencyKey);
            } catch (CoreApiException second) {
                throw unknownIfServerSide(second);
            }
        }
    }

    /**
     * 연장도 새 예약을 만드는 쓰기라 예약 만들기와 같이 다룬다: 응답이 안 오면 같은 멱등 키로 한 번만 다시 보내고,
     * 5xx면 연장됐는지 모른다고 안내한다 (다시 누르면 새 토큰 = 새 키라 1회 제한 409가 날 수 있다)
     */
    @Override
    public CreatedReservation continueUse(UserCredential credential, long reservationId, OffsetDateTime newEndAt,
                                          String idempotencyKey) {
        var body = new ContinueRequest(newEndAt);
        try {
            return sendContinue(credential, reservationId, body, idempotencyKey);
        } catch (CoreApiException first) {
            if (!CoreHttp.UNAVAILABLE.equals(first.code())) {
                throw continueUnknownIfServerSide(first);
            }
            try {
                return sendContinue(credential, reservationId, body, idempotencyKey);
            } catch (CoreApiException second) {
                throw continueUnknownIfServerSide(second);
            }
        }
    }

    private CreatedReservation sendContinue(UserCredential credential, long reservationId, ContinueRequest body,
                                            String idempotencyKey) {
        ResponseEntity<CoreCreated> response = core.callEntity("POST /api/reservations/{id}/continue", credential,
                rest -> rest.post().uri("/api/reservations/{id}/continue", reservationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .header(CREATED_VIA_HEADER, CREATED_VIA_AGENT)
                        .body(body),
                CoreCreated.class);
        CoreCreated c = response.getBody();
        if (!complete(c)) {
            throw continueUnknown();
        }
        return new CreatedReservation(c.id(), c.roomId(), SeoulTime.of(c.startAt()), SeoulTime.of(c.endAt()),
                c.status(), c.headcount(), c.accessCode(), response.getStatusCode().value() == 200);
    }

    private static CoreApiException continueUnknownIfServerSide(CoreApiException e) {
        return e.status() >= 500 ? continueUnknown() : e;
    }

    private static CoreApiException continueUnknown() {
        return new CoreApiException("CONTINUE_UNKNOWN", "연장됐는지 확인하지 못했어요. 다시 누르기 전에 내 예약에서 확인해 주세요.", 503);
    }

    /** 성공 카드에 필요한 값이 다 있나. 빠졌으면 처리됐을 수 있어 '실패'가 아니라 '모름'으로 안내한다 */
    private static boolean complete(CoreCreated c) {
        return c != null && c.id() > 0 && c.roomId() > 0 && c.headcount() > 0
                && c.startAt() != null && c.endAt() != null && c.status() != null && !c.status().isBlank()
                && c.accessCode() != null && !c.accessCode().isBlank();
    }

    @Override
    public StatusChange cancel(UserCredential credential, long reservationId) {
        return change(credential, reservationId, "cancel", "CANCELED");
    }

    @Override
    public StatusChange checkout(UserCredential credential, long reservationId) {
        return change(credential, reservationId, "checkout", "CHECKED_OUT");
    }

    /**
     * 취소·퇴실은 멱등 키 없이 보낸다: core가 조건부 UPDATE라 두 번째는 409로 끝난다 (설계.md 2.3).
     * 응답이 없거나 읽지 못하거나 core 5xx면 이미 처리됐을 수 있다. "다시 시도"가 아니라 내 예약부터 확인하게 안내한다
     * (다시 누르면 처리된 경우 409가 와서 실패처럼 보인다).
     */
    private StatusChange change(UserCredential credential, long reservationId, String verb, String doneStatus) {
        CoreStatus body;
        try {
            body = core.call("POST /api/reservations/{id}/" + verb, credential,
                    rest -> rest.post().uri("/api/reservations/{id}/" + verb, reservationId), CoreStatus.class);
        } catch (CoreApiException e) {
            if (e.status() >= 500) {
                throw new CoreApiException("STATUS_CHANGE_UNKNOWN",
                        "처리됐는지 확인하지 못했어요. 다시 누르기 전에 내 예약에서 먼저 확인해 주세요.", 503);
            }
            throw e;
        }
        // 여기까지 왔으면 2xx(CoreHttp가 2xx 아닌 응답은 BAD_RESPONSE로 바꾼다). 본문이 비었거나 status가 없으면 처리된 상태로 채운다.
        // id는 본문에서 읽지 않고 우리가 보낸 예약 id를 쓴다 (본문에 id가 빠져도 '모름'이나 0이 되지 않게)
        String status = body == null || body.status() == null || body.status().isBlank() ? doneStatus : body.status();
        return new StatusChange(reservationId, status);
    }

    /**
     * 5xx(닿지 못함·읽지 못함·코어 500)는 저장한 뒤에 났을 수 있다. "다시 시도"로 안내하면 새 확인 토큰 = 새 멱등 키라
     * 중복 예약이 될 수 있어 '모름'으로 바꾼다. 4xx(409 겹침 등)는 처리 안 된 것이 확실해서 그대로 둔다
     */
    private static CoreApiException unknownIfServerSide(CoreApiException e) {
        return e.status() >= 500 ? unknown() : e;
    }

    private static CoreApiException unknown() {
        return new CoreApiException("RESERVATION_UNKNOWN", "예약이 됐는지 확인하지 못했어요. 내 예약에서 확인해 주세요.", 503);
    }

    private CreatedReservation send(UserCredential credential, CreateRequest body, String idempotencyKey) {
        ResponseEntity<CoreCreated> response = core.callEntity("POST /api/reservations", credential,
                rest -> rest.post().uri("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .header(CREATED_VIA_HEADER, CREATED_VIA_AGENT)
                        .body(body),
                CoreCreated.class);
        CoreCreated c = response.getBody();
        if (!complete(c)) {
            // 2xx인데 본문이 비었거나 필수 값이 빠짐: 만들어졌을 수 있으니 '실패'가 아니라 '모름'으로 안내.
            // 빈 값으로 성공 카드를 보여 주면 토큰은 이미 쓰여 학생이 바로잡을 길이 없다
            throw unknown();
        }
        return new CreatedReservation(c.id(), c.roomId(), SeoulTime.of(c.startAt()), SeoulTime.of(c.endAt()),
                c.status(), c.headcount(), c.accessCode(), response.getStatusCode().value() == 200);
    }

    /** core 상태 변경 응답에서 쓰는 값은 status뿐이다 */
    record CoreStatus(String status) {
    }

    /** core ContinueRequest 모양: 새 끝 시각만. 시작은 core가 누른 시각으로 정한다 */
    record ContinueRequest(OffsetDateTime endAt) {
    }

    record CreateRequest(int roomId, OffsetDateTime startAt, OffsetDateTime endAt,
                         List<String> participantStudentIds) {
    }

    /** core CreateReservationService.Created 모양 */
    record CoreCreated(long id, int roomId, OffsetDateTime startAt, OffsetDateTime endAt, String status,
                       int headcount, String accessCode) {
    }
}
