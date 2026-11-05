package kr.ac.chosun.mspace.assistant.mcp.schedule.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.DaySchedule.ClassOfDay;
import org.springframework.stereotype.Service;

/**
 * 그날 수업과 "수업 끝나고"의 시각을 계산한다. 시간 계산은 여기서 하고 모델은 받은 시각을 쓰기만 한다
 * (모델이 14:50 + 30분을 올림하다 틀리지 않게).
 */
@Service
public class MySchedule {

    static final String NO_SCHEDULE_NOTE =
            "아직 넣은 시간표가 없어요. 화면의 [내 시간표]에서 요일·시작·끝·과목명을 넣으면 수업 시간에 맞춰 찾아 드릴게요.";
    static final String NO_CLASS_NOTE = "이 날은 입력한 시간표에 수업이 없어요.";

    private final ScheduleRepository repository;
    private final ScheduleProperties props;
    private final Clock clock;

    public MySchedule(ScheduleRepository repository, ScheduleProperties props, Clock clock) {
        this.repository = repository;
        this.props = props;
        this.clock = clock;
    }

    /** @param date null이면 오늘(서울) */
    public DaySchedule of(UserCredential credential, LocalDate date) {
        String key = StudentKey.of(credential);
        LocalDate day = date != null ? date : LocalDate.now(clock.withZone(SeoulTime.ZONE));
        String dayName = day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
        Optional<Timetable> timetable = repository.find(key);
        if (timetable.isEmpty()) {
            return new DaySchedule(day, dayName, "NO_SCHEDULE", List.of(), null, null, props.bufferMinutes(),
                    NO_SCHEDULE_NOTE);
        }
        List<ClassOfDay> classes = timetable.get().classes().stream()
                .filter(c -> c.dayOfWeek() == day.getDayOfWeek())
                .map(c -> new ClassOfDay(c.name(), at(day, c.startTime()), at(day, c.endTime())))
                .toList();
        if (classes.isEmpty()) {
            return new DaySchedule(day, dayName, null, classes, null, null, props.bufferMinutes(), NO_CLASS_NOTE);
        }
        OffsetDateTime lastEnd = classes.stream().map(ClassOfDay::endAt).max(OffsetDateTime::compareTo).orElseThrow();
        return new DaySchedule(day, dayName, null, classes, lastEnd, searchFrom(lastEnd), props.bufferMinutes(), null);
    }

    /** 끝 + 여유를 다음 예약 칸으로 올린다: 14:50 + 30분 = 15:20 → 15:30, 15:00 + 30분 = 15:30 그대로 */
    OffsetDateTime searchFrom(OffsetDateTime lastEnd) {
        OffsetDateTime t = lastEnd.plusMinutes(props.bufferMinutes()).withSecond(0).withNano(0);
        int slot = props.slotMinutes();
        int minuteOfDay = t.getHour() * 60 + t.getMinute();
        int rest = minuteOfDay % slot;
        return rest == 0 ? t : t.plusMinutes(slot - rest);
    }

    private static OffsetDateTime at(LocalDate day, java.time.LocalTime time) {
        return day.atTime(time).atZone(SeoulTime.ZONE).toOffsetDateTime();
    }
}
