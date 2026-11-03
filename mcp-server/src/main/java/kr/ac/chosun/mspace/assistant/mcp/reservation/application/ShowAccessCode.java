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
 * 두 경로 모두 같은 규칙(대표자, 끝나지 않은 예약)을 쓴다. 최종 권한은 core가 본다(대표자가 아니면 번호를 null로 줌).
 */
@Service
public class ShowAccessCode {

    static final String NOT_OWNER = "대표자가 아니라 입실 번호를 알 수 없어요. 대표자에게 문의하세요.";
    static final String FINISHED = "이미 끝났거나 취소된 예약이라 입실 번호를 쓸 수 없어요.";
    static final String SHOWN = "학생 화면에 입실 번호 카드로 보여 줬음. 번호는 이 결과에 없고 모델은 알 수 없음. 번호를 지어내지 말 것";

    private final ReservationQueryPort reservations;
    private final AccessCodePort codes;

    public ShowAccessCode(ReservationQueryPort reservations, AccessCodePort codes) {
        this.reservations = reservations;
        this.codes = codes;
    }

    /**
     * 모델에 돌려줄 답. reservationId가 없으면 끝나지 않은 내 예약에서 고른다
     * (대표자 예약은 동시에 1건뿐이라 대표자 예약이 먼저, 없으면 참여자 예약).
     */
    public Answer check(UserCredential credential, Long reservationId) {
        List<MyReservation> mine = reservations.findMine(credential);
        MyReservation r = reservationId != null
                ? mine.stream().filter(m -> m.id() == reservationId).findFirst().orElseThrow(ShowAccessCode::notFound)
                : pickActive(mine);
        if (!r.owner()) {
            return new Answer(r.id(), false, false, NOT_OWNER);
        }
        if (!active(r)) {
            return new Answer(r.id(), true, false, FINISHED);
        }
        return new Answer(r.id(), true, true, SHOWN);
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

    private static MyReservation pickActive(List<MyReservation> mine) {
        List<MyReservation> active = mine.stream().filter(ShowAccessCode::active).toList();
        return active.stream().filter(MyReservation::owner).findFirst()
                .or(() -> active.stream().findFirst())
                .orElseThrow(() -> new CoreApiException("RESERVATION_NOT_FOUND",
                        "끝나지 않은 예약이 없어요. 입실 번호는 예약이 있을 때만 있어요.", 404));
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
     * @param cardShown true면 에이전트가 학생 화면에 입실 번호 카드를 띄운다
     */
    public record Answer(long reservationId, boolean owner, boolean cardShown, String message) {
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
