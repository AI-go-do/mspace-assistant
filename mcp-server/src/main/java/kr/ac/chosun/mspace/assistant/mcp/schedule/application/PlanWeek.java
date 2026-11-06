package kr.ac.chosun.mspace.assistant.mcp.schedule.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.room.application.AvailableRooms;
import kr.ac.chosun.mspace.assistant.mcp.room.application.AvailableRooms.FreeRange;
import kr.ac.chosun.mspace.assistant.mcp.room.application.AvailableRooms.FreeRoom;
import kr.ac.chosun.mspace.assistant.mcp.room.application.FindAvailableRooms;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.WeekPlan.DayPlan;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.WeekPlan.Option;
import org.springframework.stereotype.Service;

/**
 * "이번 주에 수업 끝나고 2시간 쓸 수 있는 날" 한 번에. 날마다 my_schedule → find_available_rooms를 이어 붙인다.
 * 다른 기능(room)은 application만 부른다(컨벤션). core 호출은 날 수만큼(7번)이라 한 번에 1초 안팎이고,
 * 도구 한 번이 모델 왕복 14번을 대신하니 전체로는 더 싸다.
 */
@Service
public class PlanWeek {

    static final int MAX_MINUTES = 240;
    static final String NO_SCHEDULE_NOTE = "아직 넣은 시간표가 없어서 날마다 %s부터 봤어요. 화면의 [내 시간표]를 넣으면 수업 끝난 뒤로 맞춰 드릴게요.";
    static final String NONE_NOTE = "이 기간에는 그 길이로 쓸 수 있는 시간이 없어요. 길이를 줄이거나 인원을 바꿔 볼 수 있어요.";
    static final String REASON_NONE = "빈 시간 없음";
    static final String REASON_LATE = "수업이 늦게 끝나 그날은 안 봄";

    private final MySchedule schedule;
    private final FindAvailableRooms rooms;
    private final ScheduleProperties props;
    private final Clock clock;

    public PlanWeek(MySchedule schedule, FindAvailableRooms rooms, ScheduleProperties props, Clock clock) {
        this.schedule = schedule;
        this.rooms = rooms;
        this.props = props;
        this.clock = clock;
    }

    /**
     * @param minutes   원하는 길이. null이면 120. 예약 칸(30분)의 배수, 30~240
     * @param headcount 사람 수. null이면 인원을 안 본다
     */
    public WeekPlan plan(UserCredential credential, Integer minutes, Integer headcount) {
        int length = minutes == null ? 120 : minutes;
        if (length < props.slotMinutes() || length > MAX_MINUTES || length % props.slotMinutes() != 0) {
            throw new CoreApiException("INVALID_REQUEST",
                    "길이는 %d분 단위로 %d분부터 %d분까지예요.".formatted(props.slotMinutes(), props.slotMinutes(), MAX_MINUTES));
        }
        LocalDate today = LocalDate.now(clock.withZone(SeoulTime.ZONE));
        LocalDate last = today.plusDays(props.planDays() - 1L);
        List<DayPlan> days = new ArrayList<>();
        String code = null;
        for (LocalDate day = today; !day.isAfter(last); day = day.plusDays(1)) {
            DaySchedule ds = schedule.of(credential, day);
            if ("NO_SCHEDULE".equals(ds.code())) {
                code = "NO_SCHEDULE";
            }
            days.add(planDay(credential, ds, length, headcount));
        }
        boolean any = days.stream().anyMatch(d -> d.option() != null);
        String note = code != null ? NO_SCHEDULE_NOTE.formatted(props.planFromTime()) : any ? null : NONE_NOTE;
        return new WeekPlan(today, last, length, code, days, note);
    }

    private DayPlan planDay(UserCredential credential, DaySchedule ds, int length, Integer headcount) {
        OffsetDateTime from;
        if (ds.searchFromAt() == null) {
            from = ds.date().atTime(props.planFromTime()).atZone(SeoulTime.ZONE).toOffsetDateTime();
        } else if (!ds.searchFromAt().toLocalDate().equals(ds.date())) {
            // 밤늦은 수업: 끝 + 여유가 다음 날로 넘어가면 그날은 안 본다 (다음 날 칸은 그 날짜 조회가 맡는다)
            return new DayPlan(ds.date(), ds.dayOfWeek(), ds.classes().size(), ds.lastClassEndAt(), ds.searchFromAt(), null, REASON_LATE);
        } else {
            from = ds.searchFromAt();
        }
        AvailableRooms found = rooms.find(ds.date(), from.toLocalTime(), null, headcount, credential);
        Option best = earliest(found, from, Duration.ofMinutes(length));
        String reason = best != null ? null : found.note() != null && found.rooms().isEmpty() ? found.note() : REASON_NONE;
        return new DayPlan(ds.date(), ds.dayOfWeek(), ds.classes().size(), ds.lastClassEndAt(), from, best, reason);
    }

    /** from 뒤로 length가 들어가는 가장 이른 시작 (같으면 방 번호 순). 구간 시작이 from보다 이르면 from부터 센다 */
    private static Option earliest(AvailableRooms found, OffsetDateTime from, Duration length) {
        Option best = null;
        for (FreeRoom room : found.rooms()) {
            for (FreeRange r : room.free()) {
                OffsetDateTime start = r.startAt().isBefore(from) ? from : r.startAt();
                if (Duration.between(start, r.endAt()).compareTo(length) < 0) {
                    continue;
                }
                if (best == null || start.isBefore(best.startAt())
                        || start.isEqual(best.startAt()) && room.roomId() < best.roomId()) {
                    best = new Option(room.roomId(), room.roomName(), start, start.plus(length));
                }
            }
        }
        return best;
    }

    static String dayName(LocalDate day) {
        return day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
    }
}
