package kr.ac.chosun.mspace.assistant.mcp.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.room.application.FindAvailableRooms;
import kr.ac.chosun.mspace.assistant.mcp.room.application.RoomDay;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.MySchedule;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.PlanWeek;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.ScheduleProperties;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.ScheduleRepository;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.Timetable;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.WeekPlan;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.WeeklyClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** week_plan: 날마다 시간표 → 빈방을 서버가 이어 붙인다 (core 없이). 2026-11-05는 목요일 */
class PlanWeekTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    static final UserCredential USER = new UserCredential(Map.of("X-User-Id", "1"));
    static final ScheduleProperties PROPS = new ScheduleProperties("memory", 30, 30, 40, 30, 3, LocalTime.of(9, 0));

    /** 목 13:00–14:50, 금 15:00–16:00 수업. 토는 없음 */
    static final Timetable TIMETABLE = new Timetable(List.of(
            new WeeklyClass(DayOfWeek.THURSDAY, LocalTime.of(13, 0), LocalTime.of(14, 50), "자료구조"),
            new WeeklyClass(DayOfWeek.FRIDAY, LocalTime.of(15, 0), LocalTime.of(16, 0), "운영체제")), null);

    static ScheduleRepository repo(Timetable t) {
        Map<String, Timetable> store = new HashMap<>();
        if (t != null) {
            store.put("u:1", t);
        }
        return new ScheduleRepository() {
            public Optional<Timetable> find(String key) { return Optional.ofNullable(store.get(key)); }
            public void save(String key, Timetable timetable) { store.put(key, timetable); }
            public void delete(String key) { store.remove(key); }
        };
    }

    /** 날짜별 찬 칸("HH:mm" 시작). 방 1개(1번 방 3~5명) */
    static FindAvailableRooms rooms(Map<LocalDate, Set<String>> busyByDay) {
        return new FindAvailableRooms((date, credential) -> {
            List<RoomDay.Slot> slots = new ArrayList<>();
            Set<String> busy = busyByDay.getOrDefault(date, Set.of());
            for (int i = 0; i < 48; i++) {
                OffsetDateTime s = date.atStartOfDay(SeoulTime.ZONE).plusMinutes(30L * i).toOffsetDateTime();
                slots.add(new RoomDay.Slot(s, s.plusMinutes(30), !busy.contains(s.toLocalTime().toString())));
            }
            return new RoomDay(date, List.of(new RoomDay.RoomSlots(1, "1번 방", 3, 5, slots)));
        });
    }

    static PlanWeek planWeek(Timetable t, Map<LocalDate, Set<String>> busy) {
        Clock clock = Clock.fixed(TODAY.atTime(8, 0).atZone(SeoulTime.ZONE).toInstant(), SeoulTime.ZONE);
        return new PlanWeek(new MySchedule(repo(t), PROPS, clock), rooms(busy), PROPS, clock);
    }

    static Set<String> busyBetween(String from, String to) {
        Set<String> busy = new java.util.HashSet<>();
        for (LocalTime x = LocalTime.parse(from); x.isBefore(LocalTime.parse(to)); x = x.plusMinutes(30)) {
            busy.add(x.toString());
        }
        return busy;
    }

    @Test
    @DisplayName("날마다 수업 끝+30분(칸 올림)부터 원하는 길이가 들어가는 가장 이른 시간을 준다. 수업 없는 날은 09:00부터")
    void plansEachDay() {
        // given: 목 15:30 뒤 15:30–17:00 참 → 17:00, 금 16:30 뒤 비어 있음 → 16:30, 토 수업 없음 → 09:00
        var plan = planWeek(TIMETABLE, Map.of(TODAY, busyBetween("15:30", "17:00")));

        // when
        WeekPlan result = plan.plan(USER, 120, null);

        // then
        assertThat(result.code()).isNull();
        assertThat(result.days()).hasSize(3);
        assertThat(result.days()).extracting(d -> d.option().startAt().toString())
                .containsExactly("2026-11-05T17:00+09:00", "2026-11-06T16:30+09:00", "2026-11-07T09:00+09:00");
        assertThat(result.days().get(0).option().endAt().toString()).isEqualTo("2026-11-05T19:00+09:00");
        assertThat(result.days().get(0).lastClassEndAt().toString()).isEqualTo("2026-11-05T14:50+09:00");
        assertThat(result.days().get(2).classCount()).isZero();
        assertThat(result.note()).isNull();
    }

    @Test
    @DisplayName("쓸 수 있는 날이 없으면 reason과 안내 문장, 시간표가 없으면 NO_SCHEDULE로 매일 09:00부터 본 결과")
    void noneAndNoSchedule() {
        // given: 사흘 모두 09:00 이후 다 참
        Map<LocalDate, Set<String>> full = Map.of(
                TODAY, busyBetween("09:00", "23:30"), TODAY.plusDays(1), busyBetween("09:00", "23:30"),
                TODAY.plusDays(2), busyBetween("09:00", "23:30"));

        // when
        WeekPlan none = planWeek(TIMETABLE, full).plan(USER, null, null);
        WeekPlan noSchedule = planWeek(null, Map.of()).plan(USER, 60, null);

        // then
        assertThat(none.minutes()).isEqualTo(120);
        assertThat(none.days()).allSatisfy(d -> {
            assertThat(d.option()).isNull();
            assertThat(d.reason()).isNotBlank();
        });
        assertThat(none.note()).contains("길이를 줄이거나");
        assertThat(noSchedule.code()).isEqualTo("NO_SCHEDULE");
        assertThat(noSchedule.note()).contains("09:00", "[내 시간표]");
        assertThat(noSchedule.days()).extracting(d -> d.option().startAt().toLocalTime().toString()).containsOnly("09:00");
    }

    @Test
    @DisplayName("인원 때문에 방이 없으면 그날 reason에 인원 안내, 길이가 칸에 안 맞으면 INVALID_REQUEST")
    void headcountAndInvalidLength() {
        // given
        var plan = planWeek(TIMETABLE, Map.of());

        // when
        WeekPlan tooFew = plan.plan(USER, 60, 2);

        // then
        assertThat(tooFew.days()).allSatisfy(d -> assertThat(d.reason()).startsWith("2명이 쓸 수 있는 방이 없어요"));
        assertThatThrownBy(() -> plan.plan(USER, 45, null)).isInstanceOf(CoreApiException.class).hasMessageContaining("30분 단위");
        assertThatThrownBy(() -> plan.plan(USER, 270, null)).isInstanceOf(CoreApiException.class);
    }

    @Test
    @DisplayName("하루 조회가 실패하면 그날만 reason에 적고 나머지는 돌려준다. 401은 전체 오류로 올린다")
    void oneDayFails() {
        // given: 둘째 날 core 5xx, 나머지 비어 있음
        Clock clock = Clock.fixed(TODAY.atTime(8, 0).atZone(SeoulTime.ZONE).toInstant(), SeoulTime.ZONE);
        FindAvailableRooms flaky = new FindAvailableRooms((date, credential) -> {
            if (date.equals(TODAY.plusDays(1))) {
                throw new CoreApiException("CORE_UNAVAILABLE", "예약 시스템에 닿지 못했어요.", 503);
            }
            return day(date);
        });
        FindAvailableRooms unauthorized = new FindAvailableRooms((date, credential) -> {
            throw new CoreApiException("UNAUTHORIZED", "로그인이 필요해요.", 401);
        });
        var plan = new PlanWeek(new MySchedule(repo(TIMETABLE), PROPS, clock), flaky, PROPS, clock);
        var denied = new PlanWeek(new MySchedule(repo(TIMETABLE), PROPS, clock), unauthorized, PROPS, clock);

        // when
        WeekPlan result = plan.plan(USER, 60, null);

        // then
        assertThat(result.days()).hasSize(3);
        assertThat(result.days().get(0).option()).isNotNull();
        assertThat(result.days().get(1).option()).isNull();
        assertThat(result.days().get(1).reason()).contains("조회에 실패");
        assertThat(result.days().get(2).option()).isNotNull();
        assertThatThrownBy(() -> denied.plan(USER, 60, null)).isInstanceOf(CoreApiException.class).hasMessageContaining("로그인");
    }

    static RoomDay day(LocalDate date) {
        List<RoomDay.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            OffsetDateTime s = date.atStartOfDay(SeoulTime.ZONE).plusMinutes(30L * i).toOffsetDateTime();
            slots.add(new RoomDay.Slot(s, s.plusMinutes(30), true));
        }
        return new RoomDay(date, List.of(new RoomDay.RoomSlots(1, "1번 방", 3, 5, slots)));
    }
}
