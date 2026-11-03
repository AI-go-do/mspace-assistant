package kr.ac.chosun.mspace.assistant.mcp.reservation.application;

import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import org.springframework.stereotype.Service;

/**
 * 입실 번호 안내 (11/3 결정: 번호는 모델을 거치지 않는다).
 * <ul>
 *   <li>{@link #check}: MCP 도구용. 대표자인지와 안내 문장만. 번호는 없다</li>
 *   <li>{@link #reveal}: 화면 카드(REST)용. 대표자 본인에게만 번호를 준다</li>
 * </ul>
 * 예약을 말하지 않았고 끝나지 않은 예약이 여러 개면 추측하지 않고 고를 목록을 준다 (11/3 영준).
 * 두 경로 모두 같은 규칙(대표자, 끝나지 않은 예약)을 쓴다. 최종 권한은 core가 본다(대표자가 아니면 번호를 null로 줌).
 */
@Service
public class ShowAccessCode {

    static final String NOT_OWNER = "대표자가 아니라 입실 번호를 알 수 없어요. 대표자에게 문의하세요.";
    static final String FINISHED = "이미 끝났거나 취소된 예약이라 입실 번호를 쓸 수 없어요.";
    static final String CHOOSE = "지금 잡혀 있는 예약이 %d개예요. choices를 학생에게 보여 주고 어느 예약인지 물은 뒤 reservationId를 넣어 다시 부를 것";
    static final String SHOWN = "학생 화면에 입실 번호 카드로 보여 줬음. 번호는 이 결과에 없고 모델은 알 수 없음. 번호를 지어내지 말 것";

    private final ReservationQueryPort reservations;
    private final AccessCodePort codes;

    public ShowAccessCode(ReservationQueryPort reservations, AccessCodePort codes) {
        this.reservations = reservations;
        this.codes = codes;
    }

    /**
     * 모델에 돌려줄 답. reservationId가 없으면 끝나지 않은 내 예약을 본다:
     * 1건이면 그 예약, 여러 건이면 고를 목록(choices)을 돌려주고 모델이 학생에게 어느 예약인지 묻는다.
     */
    public Answer check(UserCredential credential, Long reservationId) {
        List<MyReservation> mine = reservations.findMine(credential);
        if (reservationId == null) {
            List<MyReservation> active = mine.stream().filter(ShowAccessCode::active).toList();
            if (active.isEmpty()) {
                throw new CoreApiException("RESERVATION_NOT_FOUND",
                        "지금 잡혀 있는 예약이 없어요. 예약을 하면 입실 번호가 생겨요.", 404);
            }
            if (active.size() > 1) {
                return new Answer(null, null, false, CHOOSE.formatted(active.size()),
                        active.stream().map(Choice::of).toList());
            }
            return answer(active.getFirst());
        }
        return answer(mine.stream().filter(m -> m.id() == reservationId).findFirst()
                .orElseThrow(ShowAccessCode::notFound));
    }

    private static Answer answer(MyReservation r) {
        if (!r.owner()) {
            return new Answer(r.id(), false, false, NOT_OWNER, List.of());
        }
        if (!active(r)) {
            return new Answer(r.id(), true, false, FINISHED, List.of());
        }
        return new Answer(r.id(), true, true, SHOWN, List.of());
    }

    /** 화면 카드가 부른다. 대표자가 아니면 403, 끝난 예약이면 409 */
    public Revealed reveal(UserCredential credential, long reservationId) {
        var found = codes.findWithAccessCode(credential, reservationId).orElseThrow(ShowAccessCode::notFound);
        MyReservation r = found.reservation();
        if (!r.owner()) {
            throw new CoreApiException("NOT_RESERVATION_OWNER", NOT_OWNER, 403);
        }
        if (!active(r)) {
            throw new CoreApiException("RESERVATION_FINISHED", FINISHED, 409);
        }
        if (found.accessCode() == null || found.accessCode().isBlank()) {
            // 대표자인데 core가 번호를 안 줌: 합의(11/2)와 다른 응답. 학생에게는 예약 화면을 보라고 안내
            throw new CoreApiException("ACCESS_CODE_UNAVAILABLE", "입실 번호를 불러오지 못했어요. 예약 화면에서 확인해 주세요.", 502);
        }
        return new Revealed(r.id(), r.roomName(), r.startAt(), r.endAt(), found.accessCode());
    }

    /** 체크인할 수 있는 상태: 예약됨, 사용 중 */
    private static boolean active(MyReservation r) {
        return r.status() == ReservationStatus.BOOKED || r.status() == ReservationStatus.IN_USE;
    }

    private static CoreApiException notFound() {
        return new CoreApiException("RESERVATION_NOT_FOUND", "찾을 수 없는 예약이에요. 내 예약에서 다시 골라 주세요.", 404);
    }

    /**
     * 모델이 읽는 답. 번호 필드가 아예 없다.
     *
     * @param reservationId 고를 예약이 여러 개면 null
     * @param owner         고를 예약이 여러 개면 null
     * @param cardShown     true면 에이전트가 학생 화면에 입실 번호 카드를 띄운다
     * @param choices       고를 예약이 여러 개일 때만 채운다 (번호 없음)
     */
    public record Answer(Long reservationId, Boolean owner, boolean cardShown, String message, List<Choice> choices) {
    }

    /** 고를 예약 한 건. 학생이 "몇 시 거"인지 알아볼 만큼만 */
    public record Choice(long reservationId, String roomName, OffsetDateTime startAt, OffsetDateTime endAt,
                         boolean owner) {

        static Choice of(MyReservation r) {
            return new Choice(r.id(), r.roomName(), r.startAt(), r.endAt(), r.owner());
        }
    }

    /** 화면 카드용. 모델·로그로 보내지 않는다 */
    public record Revealed(long reservationId, String roomName, OffsetDateTime startAt, OffsetDateTime endAt,
                           String accessCode) {

        @Override
        public String toString() {
            return "Revealed[reservationId=" + reservationId + "]";
        }
    }
}
