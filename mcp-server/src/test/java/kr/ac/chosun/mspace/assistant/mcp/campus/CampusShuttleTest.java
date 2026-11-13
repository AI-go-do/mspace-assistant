package kr.ac.chosun.mspace.assistant.mcp.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
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
        return new CampusProperties(null, new CampusProperties.Shuttle(null, routes));
    }

    static CampusProperties.Route route(String... times) {
        return new CampusProperties.Route("교내 순환", "IT융합대학 앞", null,
                java.util.Arrays.stream(times).map(LocalTime::parse).toList());
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
}
