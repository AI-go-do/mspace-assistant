package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 교내 셔틀 다음 출발. 시간표(설정값)만 보고, 실시간 위치는 모른다 (11차 회의: "실시간 아님"을 밝힌다).
 * 시간표가 없으면 시각을 지어내지 않고 NO_TIMETABLE로 답한다.
 */
@Service
public class CampusShuttle {

    static final int NEXT_COUNT = 3;
    static final String NO_TIMETABLE_NOTE = "교내 셔틀 시간표가 아직 등록되지 않았어요. 학교 안내를 확인해 주세요.";
    static final String NO_SERVICE_NOTE = "이 날은 등록된 셔틀 운행이 없어요.";
    static final String NONE_LEFT_NOTE = "이 시각 뒤로는 남은 셔틀이 없어요.";

    private final CampusProperties props;
    private final Clock clock;

    public CampusShuttle(CampusProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /**
     * @param date  null이면 오늘
     * @param after null이면 오늘은 지금, 다른 날은 하루 처음부터
     */
    public ShuttleTimes next(LocalDate date, LocalTime after) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime.ZONE));
        LocalDate day = date == null ? now.toLocalDate() : date;
        // 오늘 기본값은 초까지 그대로 비교한다: 12:10:45에 12:10 차를 "다음 차"로 안내하지 않게 (HH:mm은 응답에서만)
        LocalTime from = after != null ? after : day.equals(now.toLocalDate()) ? now.toLocalTime() : LocalTime.MIN;
        String dayName = day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
        CampusProperties.Shuttle shuttle = props.shuttle();
        if (shuttle.routes().isEmpty()) {
            return new ShuttleTimes(day, dayName, hhmm(from), "NO_TIMETABLE", List.of(), shuttle.basis(), NO_TIMETABLE_NOTE);
        }
        List<ShuttleTimes.RouteTimes> routes = shuttle.routes().stream()
                .filter(r -> r.days().contains(day.getDayOfWeek()) && !r.departures().isEmpty())
                .map(r -> new ShuttleTimes.RouteTimes(r.name(), r.stop(),
                        r.departures().stream().filter(t -> !t.isBefore(from)).limit(NEXT_COUNT).map(CampusShuttle::hhmm).toList(),
                        hhmm(r.departures().getLast())))
                .toList();
        String note = routes.isEmpty() ? NO_SERVICE_NOTE
                : routes.stream().allMatch(r -> r.next().isEmpty()) ? NONE_LEFT_NOTE : null;
        return new ShuttleTimes(day, dayName, hhmm(from), null, routes, shuttle.basis(), note);
    }

    private static String hhmm(LocalTime t) {
        return "%02d:%02d".formatted(t.getHour(), t.getMinute());
    }
}
