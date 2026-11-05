package kr.ac.chosun.mspace.assistant.mcp.reservation.adapter.out.core;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreHttp;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.AccessCodePort;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.MyReservation;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationQueryPort;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationStatus;
import org.springframework.stereotype.Component;

/**
 * 예약 코어의 내 예약 API. 응답 모양(CoreReservation)은 11/2 지영님과 합의한 모양이다 (docs/도구-명세.md 2.2).
 * 코어 PR이 나오면 실제 응답과 다시 대조한다.
 */
@Component
class CoreReservationClient implements ReservationQueryPort, AccessCodePort {

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

    /**
     * 입실 번호 카드 전용. 같은 /me를 다시 부르되 이 경로에서만 accessCode를 읽는다.
     * core에 예약 한 건 조회 API가 생기면 그쪽으로 바꾼다 (확인 필요).
     */
    @Override
    public Optional<WithAccessCode> findWithAccessCode(UserCredential credential, long reservationId) {
        CoreReservationWithCode[] body = core.call("GET /api/reservations/me (access-code)", credential,
                rest -> rest.get().uri("/api/reservations/me"), CoreReservationWithCode[].class);
        if (body == null) {
            return Optional.empty();
        }
        return List.of(body).stream().filter(r -> r.id() == reservationId).findFirst()
                .map(r -> new WithAccessCode(new CoreReservation(r.id(), r.room(), r.startAt(), r.endAt(),
                        r.status(), r.owner(), r.participants()).toMine(), r.accessCode()));
    }

    /**
     * 코어 응답 한 건. 여기 적힌 필드만 읽는다. accessCode(입실 번호)와 참여자 이름은 아예 읽지 않는다.
     * 대표자 전용 값이라 비서 쪽에서는 쓸 일이 없다 (입실 번호는 화면 카드로만 보여 준다, 설계 3장).
     */
    record CoreReservation(long id, CoreRoom room, OffsetDateTime startAt, OffsetDateTime endAt,
                           String status, boolean owner, List<CoreParticipant> participants) {

        MyReservation toMine() {
            int count = participants == null ? 0 : participants.size();
            return new MyReservation(id, room == null ? null : room.name(),
                    SeoulTime.of(startAt), SeoulTime.of(endAt), ReservationStatus.fromCore(status), owner, count);
        }
    }

    /** 입실 번호까지 읽는 모양. findWithAccessCode에서만 쓴다 */
    record CoreReservationWithCode(long id, CoreRoom room, OffsetDateTime startAt, OffsetDateTime endAt,
                                   String status, boolean owner, String accessCode,
                                   List<CoreParticipant> participants) {

        @Override
        public String toString() {
            return "CoreReservationWithCode[id=" + id + "]";
        }
    }

    record CoreRoom(int id, String name) {
    }

    /** 참여자 수만 센다. 이름은 읽지 않는다 */
    record CoreParticipant(boolean owner) {
    }
}
