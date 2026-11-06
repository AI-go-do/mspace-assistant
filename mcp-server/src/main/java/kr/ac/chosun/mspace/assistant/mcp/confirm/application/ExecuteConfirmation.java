package kr.ac.chosun.mspace.assistant.mcp.confirm.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationCommandPort;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationCommandPort.CreatedReservation;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationCommandPort.StatusChange;
import org.springframework.stereotype.Service;

/**
 * 쓰기 도구 2단계: 학생이 [확인]을 눌렀을 때만 실행한다. 이 경로에는 모델이 없다.
 * 토큰은 꺼내는 순간 쓴 것으로 처리돼서, 코어가 실패해도 같은 토큰으로 다시 실행할 수 없다(다시 말해야 함).
 */
@Service
public class ExecuteConfirmation {

    private final ConfirmationStore store;
    private final ReservationCommandPort reservations;
    private final Clock clock;

    public ExecuteConfirmation(ConfirmationStore store, ReservationCommandPort reservations, Clock clock) {
        this.store = store;
        this.reservations = reservations;
        this.clock = clock;
    }

    public Executed execute(String token, UserCredential credential, List<String> participantStudentIds) {
        // 입력이 틀렸으면 토큰을 쓰지 않고 돌려보낸다(학생이 학번만 고쳐 다시 누를 수 있게)
        List<String> participants = participantStudentIds == null ? List.of()
                : participantStudentIds.stream().map(ExecuteConfirmation::stripOrEmpty).toList();
        switch (store.peek(token, credential)) {
            case PendingAction.Create c -> checkParticipants(c.headcount(), participants);
            case PendingAction.StartNow s -> {
                checkParticipants(s.headcount(), participants);
                checkStillAhead(s.endAt());
            }
            default -> {
                // 취소·퇴실·연장은 미리 볼 입력이 없다
            }
        }
        PendingAction action = store.take(token, credential);
        return switch (action) {
            case PendingAction.Create c -> create(token, credential, c, participants);
            case PendingAction.StartNow s -> {
                CreatedReservation created = reservations.startNow(credential, s.roomId(), s.endAt(), participants, token);
                yield new Executed("START_NOW", created.id(), created.status(), created.accessCode(), created.replayed(),
                        "지금부터 %s까지 예약했어요. 10분 안에 입실해 주세요.".formatted(created.endAt().toLocalTime()));
            }
            case PendingAction.Cancel(long reservationId) -> changed("CANCEL", reservations.cancel(credential, reservationId), "취소했어요.");
            case PendingAction.Checkout(long reservationId) -> changed("CHECKOUT", reservations.checkout(credential, reservationId),
                    "퇴실했어요. 방이 바로 비었어요.");
            case PendingAction.Continue(long reservationId, OffsetDateTime newEndAt) -> {
                CreatedReservation created = reservations.continueUse(credential, reservationId, newEndAt, token);
                // 연장하면 새 예약이 생기고 입실 번호도 새로 나온다. 번호는 학생 화면에만 (모델에는 에이전트가 뺀다)
                yield new Executed("CONTINUE", created.id(), created.status(), created.accessCode(), created.replayed(),
                        "%s까지 연장했어요. 입실 번호가 새로 나왔어요.".formatted(created.endAt().toLocalTime()));
            }
        };
    }

    private static String stripOrEmpty(String s) {
        return s == null ? "" : s.strip();
    }

    /** 즉시 시작은 확인을 누른 시각부터라, 제안 뒤 끝 시각이 지나 버렸으면 토큰을 쓰지 않고 다시 제안하게 한다 */
    private void checkStillAhead(OffsetDateTime endAt) {
        OffsetDateTime now = clock.instant().atZone(SeoulTime.ZONE).toOffsetDateTime();
        if (!endAt.isAfter(now)) {
            throw new CoreApiException("RESERVATION_INVALID_LENGTH",
                    "끝 시각 " + endAt.toLocalTime() + "이 이미 지났어요. 끝 시각을 다시 골라 주세요.");
        }
    }

    private static void checkParticipants(int headcount, List<String> participants) {
        if (participants.size() != headcount - 1 || participants.stream().anyMatch(String::isEmpty)) {
            throw new CoreApiException("INVALID_REQUEST",
                    "함께 쓰는 " + (headcount - 1) + "명의 학번을 모두 입력해 주세요.");
        }
    }

    private Executed create(String token, UserCredential credential, PendingAction.Create c,
                            List<String> participants) {
        CreatedReservation created = reservations.create(credential, c.roomId(), c.startAt(), c.endAt(),
                participants, token);
        return new Executed("CREATE", created.id(), created.status(), created.accessCode(), created.replayed(),
                "예약했어요.");
    }

    private static Executed changed(String action, StatusChange change, String message) {
        return new Executed(action, change.id(), change.status(), null, false, message);
    }

    /**
     * 확인 API 응답. accessCode는 학생 화면 전용이다. 에이전트가 이 결과를 모델에 넘길 때는 accessCode를 뺀다.
     */
    public record Executed(String action, long reservationId, String status, String accessCode, boolean replayed,
                           String message) {
    }
}
