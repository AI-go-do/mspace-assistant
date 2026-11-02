package kr.ac.chosun.mspace.assistant.mcp.reservation.adapter.out.core;

import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreHttp;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.MyReservation;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationQueryPort;
import org.springframework.stereotype.Component;

/**
 * 예약 코어의 내 예약 API. 응답 모양(CoreReservation)은 11/2 지영님과 합의한 모양이다 (docs/도구-명세.md 2.2).
 * 코어 PR이 나오면 실제 응답과 다시 대조한다.
 */
@Component
class CoreReservationClient implements ReservationQueryPort {

    private final CoreHttp core;

    CoreReservationClient(CoreHttp core) {
        this.core = core;
    }

    @Override
    public List<MyReservation> findMine(UserCredential credential) {
        CoreReservation[] body = core.call("GET /api/reservations/me", credential,
                rest -> rest.get().uri("/api/reservations/me"), CoreReservation[].class);
        return body == null ? List.of() : List.of(body).stream().map(CoreReservation::toMine).toList();
    }

    /** 코어 응답 한 건. 모르는 필드는 무시한다. accessCode·참여자 이름은 여기서 버린다 */
    record CoreReservation(long id, CoreRoom room, OffsetDateTime startAt, OffsetDateTime endAt,
                           String status, boolean owner, List<CoreParticipant> participants,
                           String accessCode) {

        MyReservation toMine() {
            int count = participants == null ? 0 : participants.size();
            return new MyReservation(id, room == null ? null : room.name(),
                    SeoulTime.of(startAt), SeoulTime.of(endAt), status, owner, count);
        }
    }

    record CoreRoom(int id, String name) {
    }

    record CoreParticipant(String name, boolean owner) {
    }
}
