package kr.ac.chosun.mspace.assistant.mcp.confirm.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.MyReservation;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationQueryPort;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationStatus;
import org.springframework.stereotype.Service;

/** 쓰기 도구 1단계: 확인안 문장과 확인 토큰을 만든다. 쓰기 API는 부르지 않는다(취소·퇴실은 내 예약 조회만) */
@Service
public class ProposeChange {

    static final String MIDNIGHT_NOTE = "자정 이후 부분은 예약할 때 확인돼요.";

    private final ConfirmationStore store;
    private final ReservationQueryPort reservations;
    private final Clock clock;

    public ProposeChange(ConfirmationStore store, ReservationQueryPort reservations, Clock clock) {
        this.store = store;
        this.reservations = reservations;
        this.clock = clock;
    }

    /**
     * 취소 확인안. 내용은 내 예약 목록에서 채운다. 안 될 게 뻔한 경우(대표자 아님, 이미 시작)는 여기서 먼저 알려 주지만,
     * 최종 판단은 실행할 때 core가 한다 (그 사이 상태가 바뀔 수 있음).
     */
    public Proposal cancel(UserCredential credential, long reservationId) {
        MyReservation r = mine(credential, reservationId);
        requireOwner(r, "취소");
        if (r.status() != ReservationStatus.BOOKED) {
            throw new CoreApiException("RESERVATION_NOT_CANCELABLE",
                    r.status() == ReservationStatus.IN_USE ? "사용 중인 예약은 취소할 수 없어요. 퇴실을 해 주세요."
                            : "이미 끝났거나 취소된 예약이에요.", 409);
        }
        // BOOKED여도 시작 시각이 지났으면(노쇼 판정 전 15분) core가 409로 막는다. 확인을 누른 뒤 실패하지 않게 먼저 거른다
        if (r.startAt() != null && !clock.instant().isBefore(r.startAt().toInstant())) {
            throw new CoreApiException("RESERVATION_NOT_CANCELABLE",
                    "이미 시작 시각이 지난 예약은 취소할 수 없어요. 쓰지 않으면 노쇼로 처리돼요.", 409);
        }
        String summary = "%s 예약을 취소할까요?".formatted(describe(r));
        var issued = store.issue(credential, new PendingAction.Cancel(reservationId));
        return new Proposal(issued.token(), seoul(issued.expiresAt()), summary, List.of(), 0);
    }

    /** 퇴실 확인안. 끝나기 전이면 조기 퇴실이라고 알린다 (FR-RSV-008) */
    public Proposal checkout(UserCredential credential, long reservationId) {
        MyReservation r = mine(credential, reservationId);
        requireOwner(r, "퇴실");
        if (r.status() != ReservationStatus.IN_USE) {
            throw new CoreApiException("RESERVATION_NOT_IN_USE",
                    r.status() == ReservationStatus.BOOKED ? "아직 입실하지 않은 예약이에요. 쓰지 않을 거면 취소해 주세요."
                            : "이미 끝난 예약이에요.", 409);
        }
        List<String> notes = new ArrayList<>();
        if (r.endAt() != null && clock.instant().isBefore(r.endAt().toInstant())) {
            notes.add("끝나는 시각(" + r.endAt().toLocalTime() + ") 전이라 조기 퇴실이에요. 퇴실하면 방이 바로 비어요.");
        }
        String summary = "%s 사용을 끝내고 퇴실할까요?".formatted(describe(r));
        var issued = store.issue(credential, new PendingAction.Checkout(reservationId));
        return new Proposal(issued.token(), seoul(issued.expiresAt()), summary, notes, 0);
    }

    private MyReservation mine(UserCredential credential, long reservationId) {
        MyReservation r = reservations.findMine(credential).stream().filter(m -> m.id() == reservationId).findFirst()
                .orElseThrow(() -> new CoreApiException("RESERVATION_NOT_FOUND",
                        "찾을 수 없는 예약이에요. 내 예약에서 다시 골라 주세요.", 404));
        // 시각·방 이름이 빠진 응답으로 확인안을 만들면 시작 시각 검사를 건너뛰거나 "null …" 문장이 나간다.
        if (r.startAt() == null || r.endAt() == null || r.roomName() == null) {
            throw new CoreApiException("CORE_ERROR", "예약 정보를 읽지 못했어요. 내 예약에서 확인해 주세요.", 502);
        }
        return r;
    }

    private static void requireOwner(MyReservation r, String verb) {
        if (!r.owner()) {
            throw new CoreApiException("NOT_RESERVATION_OWNER", "대표자만 " + verb + "할 수 있어요.", 403);
        }
    }

    /** 1번 방 11월 3일(화) 14:00–16:00 */
    private static String describe(MyReservation r) {
        return "%s %s %s–%s".formatted(r.roomName(), day(r.startAt()), r.startAt().toLocalTime(), r.endAt().toLocalTime());
    }

    private static OffsetDateTime seoul(java.time.Instant t) {
        return t.atZone(SeoulTime.ZONE).toOffsetDateTime();
    }

    public Proposal create(UserCredential credential, int roomId, OffsetDateTime startAt, OffsetDateTime endAt,
                           int headcount) {
        if (headcount < 1 || !endAt.isAfter(startAt)) {
            throw new CoreApiException("INVALID_REQUEST", "인원은 1명 이상, 끝 시각은 시작보다 뒤여야 해요.");
        }
        OffsetDateTime start = SeoulTime.of(startAt);
        OffsetDateTime end = SeoulTime.of(endAt);
        List<String> notes = new ArrayList<>();
        boolean nextDay = end.toLocalDate().isAfter(start.toLocalDate())
                && !(end.toLocalDate().equals(start.toLocalDate().plusDays(1)) && end.toLocalTime().equals(java.time.LocalTime.MIDNIGHT));
        if (nextDay) {
            notes.add(MIDNIGHT_NOTE);
        }
        int others = headcount - 1;
        if (others > 0) {
            notes.add("함께 쓰는 " + others + "명의 학번은 확인 화면에서 입력해요.");
        }
        String summary = "%d번 방, %s %s–%s, %d명으로 예약할까요?".formatted(
                roomId, day(start), start.toLocalTime(), end.toLocalTime(), headcount);
        var issued = store.issue(credential, new PendingAction.Create(roomId, start, end, headcount));
        return new Proposal(issued.token(), seoul(issued.expiresAt()), summary, notes, others);
    }

    /** 11월 3일(화) */
    static String day(OffsetDateTime t) {
        return "%d월 %d일(%s)".formatted(t.getMonthValue(), t.getDayOfMonth(),
                t.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN));
    }
}
