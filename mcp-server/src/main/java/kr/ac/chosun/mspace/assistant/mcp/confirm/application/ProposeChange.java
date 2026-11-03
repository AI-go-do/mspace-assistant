package kr.ac.chosun.mspace.assistant.mcp.confirm.application;

import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import org.springframework.stereotype.Service;

/** 쓰기 도구 1단계: 확인안 문장과 확인 토큰을 만든다. 코어는 부르지 않는다 */
@Service
public class ProposeChange {

    static final String MIDNIGHT_NOTE = "자정 이후 부분은 예약할 때 확인돼요.";

    private final ConfirmationStore store;

    public ProposeChange(ConfirmationStore store) {
        this.store = store;
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
        return new Proposal(issued.token(), issued.expiresAt().atZone(SeoulTime.ZONE).toOffsetDateTime(),
                summary, notes, others);
    }

    /** 11월 3일(화) */
    static String day(OffsetDateTime t) {
        return "%d월 %d일(%s)".formatted(t.getMonthValue(), t.getDayOfMonth(),
                t.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN));
    }
}
