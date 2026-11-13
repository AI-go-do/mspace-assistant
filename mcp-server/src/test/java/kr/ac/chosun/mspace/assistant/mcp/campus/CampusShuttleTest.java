package kr.ac.chosun.mspace.assistant.mcp.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CampusProperties;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CampusShuttle;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.ShuttleTimes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 셔틀 다음 출발 계산. 시각은 테스트용 가짜 시간표 */
class CampusShuttleTest {

    /** 2026-11-13(금) 12:05 서울 */
    static final Clock FRIDAY_NOON = Clock.fixed(Instant.parse("2026-11-13T03:05:00Z"), ZoneId.of("UTC"));

    static CampusProperties props(List<CampusProperties.Route> routes) {
        return new CampusProperties(null, new CampusProperties.Shuttle(null, null, routes), null);
    }

    static CampusProperties.Route route(String... times) {
        return new CampusProperties.Route("교내 순환", "IT융합대학 앞", null, null,
                java.util.Arrays.stream(times).map(LocalTime::parse).toList(), null);
    }

    @Test
    @DisplayName("시각을 안 주면 오늘 지금 뒤 출발부터, 지난 차는 빼고 가까운 3개")
    void fromNow() {
        // given
        var shuttle = new CampusShuttle(props(List.of(route("18:00", "08:30", "12:00", "12:10", "13:00", "14:00"))), FRIDAY_NOON);

        // when
        ShuttleTimes t = shuttle.next(null, null);

        // then
        assertThat(t.after()).isEqualTo("12:05");
        assertThat(t.dayOfWeek()).isEqualTo("금");
        assertThat(t.routes().getFirst().next()).containsExactly("12:10", "13:00", "14:00");
        assertThat(t.routes().getFirst().last()).isEqualTo("18:00");
        assertThat(t.code()).isNull();
        assertThat(t.note()).isNull();
    }

    @Test
    @DisplayName("시간표가 없으면 NO_TIMETABLE, 시각을 지어내지 않는다")
    void noTimetable() {
        // when
        ShuttleTimes t = new CampusShuttle(props(List.of()), FRIDAY_NOON).next(null, null);

        // then
        assertThat(t.code()).isEqualTo("NO_TIMETABLE");
        assertThat(t.routes()).isEmpty();
        assertThat(t.note()).contains("아직 등록되지 않았어요");
        assertThat(t.basis()).contains("실시간 위치가 아니라");
    }

    @Test
    @DisplayName("마지막 차가 지났으면 남은 셔틀이 없다고 답한다")
    void noneLeft() {
        // when
        ShuttleTimes t = new CampusShuttle(props(List.of(route("08:30", "09:00"))), FRIDAY_NOON).next(null, null);

        // then
        assertThat(t.routes().getFirst().next()).isEmpty();
        assertThat(t.note()).contains("남은 셔틀이 없어요");
    }

    @Test
    @DisplayName("다른 날은 그날 첫 차부터 본다")
    void otherDayFromStart() {
        // when: 2026-11-16(월)
        ShuttleTimes t = new CampusShuttle(props(List.of(route("08:30", "09:00", "12:10", "13:00"))), FRIDAY_NOON)
                .next(java.time.LocalDate.parse("2026-11-16"), null);

        // then
        assertThat(t.routes().getFirst().next()).containsExactly("08:30", "09:00", "12:10");
    }

    @Test
    @DisplayName("12:10:45에는 12:10에 떠난 차를 다음 차로 안내하지 않는다")
    void secondsCount() {
        // given: 2026-11-13(금) 12:10:45 서울
        Clock clock = Clock.fixed(Instant.parse("2026-11-13T03:10:45Z"), ZoneId.of("UTC"));

        // when
        ShuttleTimes t = new CampusShuttle(props(List.of(route("12:10", "13:00"))), clock).next(null, null);

        // then
        assertThat(t.routes().getFirst().next()).containsExactly("13:00");
        assertThat(t.after()).isEqualTo("12:10");
    }

    static CampusProperties.Window window(String from, String to, Integer everyMinutes, String note) {
        return new CampusProperties.Window(LocalTime.parse(from), LocalTime.parse(to),
                everyMinutes == null ? null : Duration.ofMinutes(everyMinutes), note);
    }

    /** 학교 공지(교내버스 안내)의 본관 순환 버스 학기 중 시간표 */
    static CampusProperties.Route mainLoop(CampusProperties.Season season) {
        return new CampusProperties.Route("본관 순환 버스", "설립기념탑 → 미대", season, null, null, List.of(
                window("07:40", "17:00", null, "수시 운행(5~10분 간격)"),
                window("17:00", "18:45", 15, null),
                window("18:45", "20:00", null, "저녁 시간, 20:00에 다시 출발"),
                window("20:00", "23:00", 20, null)));
    }

    @Test
    @DisplayName("수시 운행 시간대에는 시각을 지어내지 않고 운행 시간대를 알려 주고, 간격 운행은 시각으로 펼친다")
    void windows() {
        // when: 금 12:05
        ShuttleTimes t = new CampusShuttle(props(List.of(mainLoop(null))), FRIDAY_NOON).next(null, null);

        // then
        ShuttleTimes.RouteTimes r = t.routes().getFirst();
        assertThat(r.running()).isEqualTo("07:40~17:00 수시 운행(5~10분 간격)");
        assertThat(r.next()).containsExactly("17:00", "17:15", "17:30");
        assertThat(r.last()).isEqualTo("23:00");
        assertThat(t.note()).isNull();
    }

    @Test
    @DisplayName("저녁 시간(18:45~20:00)에는 그 안내와 20:00 출발을 함께 준다")
    void dinnerBreak() {
        // when
        ShuttleTimes t = new CampusShuttle(props(List.of(mainLoop(null))), FRIDAY_NOON)
                .next(null, LocalTime.parse("19:00"));

        // then
        assertThat(t.routes().getFirst().running()).isEqualTo("18:45~20:00 저녁 시간, 20:00에 다시 출발");
        assertThat(t.routes().getFirst().next()).containsExactly("20:00", "20:20", "20:40");
    }

    @Test
    @DisplayName("방학 중 설정이면 학기 중 노선은 빼고 방학 노선만 본다")
    void seasonFilter() {
        // given
        var vacation = new CampusProperties.Route("본관 순환 버스", "설립기념탑 → 미대", CampusProperties.Season.VACATION, null,
                List.of(LocalTime.parse("12:45")), List.of(window("08:20", "09:00", 10, null)));
        var props = new CampusProperties(null, new CampusProperties.Shuttle(null, CampusProperties.Season.VACATION,
                List.of(mainLoop(CampusProperties.Season.SEMESTER), vacation)), null);

        // when
        ShuttleTimes t = new CampusShuttle(props, FRIDAY_NOON).next(null, LocalTime.parse("08:00"));

        // then
        assertThat(t.routes()).hasSize(1);
        assertThat(t.routes().getFirst().next()).containsExactly("08:20", "08:30", "08:40");
        assertThat(t.routes().getFirst().last()).isEqualTo("12:45");
    }

    @Test
    @DisplayName("수시 운행 시작 전(07:00)이면 남은 차가 없다고 하지 않고 곧 시작하는 시간대를 알려 준다")
    void upcomingWindow() {
        // given: 시각 없는 수시 운행만 있는 노선
        var route = new CampusProperties.Route("본관 순환 버스", "설립기념탑 → 미대", null, null, null,
                List.of(window("07:40", "17:00", null, "수시 운행(5~10분 간격)")));

        // when
        ShuttleTimes t = new CampusShuttle(props(List.of(route)), FRIDAY_NOON).next(null, LocalTime.parse("07:00"));

        // then
        assertThat(t.routes().getFirst().upcoming()).isEqualTo("07:40~17:00 수시 운행(5~10분 간격)");
        assertThat(t.routes().getFirst().next()).isEmpty();
        assertThat(t.note()).isNull();
    }

    @Test
    @DisplayName("하루 이상인 배차 간격은 설정에서 막는다 (펼치기가 끝나지 않으니까)")
    void dayLongInterval() {
        // when / then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> window("08:00", "09:00", 24 * 60, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
