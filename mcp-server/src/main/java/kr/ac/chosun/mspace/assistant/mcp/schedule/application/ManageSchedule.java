package kr.ac.chosun.mspace.assistant.mcp.schedule.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import org.springframework.stereotype.Service;

/**
 * 학생이 화면에서 시간표를 넣고·보고·지운다 (REST 전용, 모델은 부를 수 없다).
 * 저장 전에 모양을 검사한다: 요일, HH:mm, 시작 &lt; 끝, 같은 요일 겹침, 과목명 길이, 칸 수 상한.
 * 통째로 바꾸므로(PUT) 같은 학생이 두 화면에서 동시에 넣으면 나중 것이 남는다(마지막 쓰기 승리, 시간표라 충분).
 */
@Service
public class ManageSchedule {

    private final ScheduleRepository repository;
    private final ScheduleProperties props;
    private final Clock clock;

    public ManageSchedule(ScheduleRepository repository, ScheduleProperties props, Clock clock) {
        this.repository = repository;
        this.props = props;
        this.clock = clock;
    }

    /** 화면이 보낸 수업 한 칸 (문자열 그대로, 검사 전) */
    public record ClassInput(String dayOfWeek, String startTime, String endTime, String name) {
    }

    public Timetable replace(UserCredential credential, List<ClassInput> inputs) {
        String key = StudentKey.of(credential);
        if (inputs == null || inputs.isEmpty()) {
            throw invalid("수업을 하나 이상 넣어 주세요. 시간표를 지우려면 삭제를 눌러 주세요.");
        }
        if (inputs.size() > props.maxClasses()) {
            throw invalid("수업은 %d칸까지 넣을 수 있어요.".formatted(props.maxClasses()));
        }
        List<WeeklyClass> classes = new ArrayList<>();
        for (ClassInput input : inputs) {
            classes.add(parse(input));
        }
        rejectOverlap(classes);
        Timetable timetable = new Timetable(classes, SeoulTime.of(OffsetDateTime.now(clock)));
        repository.save(key, timetable);
        return timetable;
    }

    public Timetable find(UserCredential credential) {
        return repository.find(StudentKey.of(credential)).orElseThrow(() -> new CoreApiException(
                "NO_SCHEDULE", "아직 넣은 시간표가 없어요.", 404));
    }

    public void delete(UserCredential credential) {
        repository.delete(StudentKey.of(credential));
    }

    private WeeklyClass parse(ClassInput input) {
        if (input == null) {
            throw invalid("수업 칸이 비어 있어요.");
        }
        DayOfWeek day;
        try {
            day = DayOfWeek.valueOf(normalizeDay(input.dayOfWeek()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid("요일은 MON~SUN으로 넣어 주세요.");
        }
        LocalTime start = time(input.startTime());
        LocalTime end = time(input.endTime());
        if (!start.isBefore(end)) {
            throw invalid("수업 끝 시각은 시작보다 늦어야 해요.");
        }
        String name = input.name() == null ? "" : input.name().strip();
        if (name.isEmpty() || name.length() > props.maxNameChars()) {
            throw invalid("과목명은 1~%d자로 넣어 주세요.".formatted(props.maxNameChars()));
        }
        return new WeeklyClass(day, start, end, name);
    }

    /** MON·MONDAY·mon 모두 받는다 */
    private static String normalizeDay(String value) {
        String v = value.strip().toUpperCase(Locale.ROOT);
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().startsWith(v) && v.length() >= 3) {
                return d.name();
            }
        }
        return v;
    }

    private static LocalTime time(String value) {
        try {
            return LocalTime.parse(value.strip());
        } catch (DateTimeParseException | NullPointerException e) {
            throw invalid("시각은 HH:mm 24시간 형식으로 넣어 주세요 (예: 09:00, 14:50).");
        }
    }

    private static void rejectOverlap(List<WeeklyClass> classes) {
        List<WeeklyClass> sorted = classes.stream()
                .sorted(Comparator.comparing(WeeklyClass::dayOfWeek).thenComparing(WeeklyClass::startTime))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            WeeklyClass prev = sorted.get(i - 1);
            WeeklyClass cur = sorted.get(i);
            if (prev.dayOfWeek() == cur.dayOfWeek() && cur.startTime().isBefore(prev.endTime())) {
                throw new CoreApiException("SCHEDULE_OVERLAP",
                        "같은 요일에 겹치는 수업이 있어요: %s, %s".formatted(prev.name(), cur.name()));
            }
        }
    }

    private static CoreApiException invalid(String message) {
        return new CoreApiException("INVALID_REQUEST", message);
    }
}
