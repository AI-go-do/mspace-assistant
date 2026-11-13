package kr.ac.chosun.mspace.assistant.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 오늘 브리핑: 도구 3개 결과를 한 문단으로. 모델 없음 (도구는 가짜) */
class DailyBriefingTest {

    static final UserHeaders ME = new UserHeaders(Map.of("Authorization", "Bearer t"));
    /** 2026-11-06(금) 08:00 서울 */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T23:00:00Z"), ZoneId.of("UTC"));

    static final String FRIDAY = """
            {"date":"2026-11-06","dayOfWeek":"금","code":null,
             "classes":[{"name":"자료구조","startAt":"2026-11-06T09:00:00+09:00","endAt":"2026-11-06T10:15:00+09:00"},
                        {"name":"운영체제","startAt":"2026-11-06T13:00:00+09:00","endAt":"2026-11-06T14:50:00+09:00"}],
             "lastClassEndAt":"2026-11-06T14:50:00+09:00","searchFromAt":"2026-11-06T15:30:00+09:00","bufferMinutes":30,"note":null}""";
    static final String NO_SCHEDULE = """
            {"date":"2026-11-06","dayOfWeek":"금","code":"NO_SCHEDULE","classes":[],"lastClassEndAt":null,"searchFromAt":null,
             "bufferMinutes":30,"note":"아직 넣은 시간표가 없어요. 화면의 [내 시간표]에서 넣어 주세요."}""";
    static final String RESERVATIONS = """
            {"reservations":[
              {"id":7,"roomName":"1번 방","startAt":"2026-11-06T15:30:00+09:00","endAt":"2026-11-06T17:30:00+09:00","status":"BOOKED","owner":true,"participantCount":3},
              {"id":8,"roomName":"3번 방","startAt":"2026-11-09T10:00:00+09:00","endAt":"2026-11-09T11:00:00+09:00","status":"BOOKED","owner":false,"participantCount":3},
              {"id":5,"roomName":"2번 방","startAt":"2026-11-04T10:00:00+09:00","endAt":"2026-11-04T11:00:00+09:00","status":"CHECKED_OUT","owner":true,"participantCount":3}]}""";
    static final String STATUS = """
            {"now":"2026-11-06T08:00:00+09:00","availableCount":2,"rooms":[
              {"roomId":1,"roomName":"1번 방","status":"IN_USE","latestEndAt":null},
              {"roomId":2,"roomName":"2번 방","status":"AVAILABLE","latestEndAt":"2026-11-06T10:00:00+09:00"},
              {"roomId":3,"roomName":"3번 방","status":"AVAILABLE","latestEndAt":"2026-11-06T09:30:00+09:00"}],"note":null}""";

    /** 도구 이름 → 결과. null이면 오류 */
    static ToolGateway fake(Map<String, String> results, List<String> called) {
        return headers -> new ToolGateway.Session() {
            @Override
            public List<ToolSpec> tools() {
                return List.of();
            }

            @Override
            public ToolGateway.Outcome call(String name, Map<String, Object> arguments) {
                called.add(name + arguments);
                String r = results.get(name);
                return r == null ? new ToolGateway.Outcome("{\"message\":\"실패\"}", true) : new ToolGateway.Outcome(r, false);
            }

            @Override
            public void close() {
                // 가짜 세션이라 닫을 연결이 없다
            }
        };
    }

    static final String FREE = """
            {"date":"2026-11-06","rooms":[
              {"roomId":3,"roomName":"3번 방","minPeople":3,"maxPeople":5,"free":[{"startAt":"2026-11-06T15:30:00+09:00","endAt":"2026-11-06T16:30:00+09:00"}]},
              {"roomId":2,"roomName":"2번 방","minPeople":3,"maxPeople":5,"free":[{"startAt":"2026-11-06T14:00:00+09:00","endAt":"2026-11-06T19:00:00+09:00"}]},
              {"roomId":1,"roomName":"1번 방","minPeople":3,"maxPeople":5,"free":[{"startAt":"2026-11-06T16:00:00+09:00","endAt":"2026-11-06T22:00:00+09:00"}]},
              {"roomId":4,"roomName":"4번 방","minPeople":3,"maxPeople":5,"free":[{"startAt":"2026-11-06T10:30:00+09:00","endAt":"2026-11-06T12:30:00+09:00"}]}],
             "note":null,"alternatives":[]}""";

    static DailyBriefing briefing(ToolGateway tools) {
        return briefing(tools, CLOCK);
    }

    static DailyBriefing briefing(ToolGateway tools, Clock clock) {
        return new DailyBriefing(tools, clock, JsonMapper.builder().build(), new AgentProperties.Rules(30, 2, 7));
    }

    @Test
    @DisplayName("정상: 오늘 수업 2개·오늘 예약(대표자)·지금 빈 방 2곳을 한 문단으로. my_schedule은 오늘 날짜로 부른다")
    void fullDay() {
        // given
        List<String> called = new ArrayList<>();
        var tools = fake(Map.of("my_schedule", FRIDAY, "my_reservations", RESERVATIONS, "room_status", STATUS), called);

        // when
        DailyBriefing.Briefing b = briefing(tools).today(ME);

        // then
        assertThat(called).containsExactly("my_schedule{date=2026-11-06}", "my_reservations{}", "room_status{}");
        assertThat(b.date()).isEqualTo("2026-11-06");
        assertThat(b.schedule().classes()).extracting(DailyBriefing.ClassItem::name).containsExactly("자료구조", "운영체제");
        assertThat(b.schedule().lastClassEnd()).isEqualTo("14:50");
        // 끝난 예약(CHECKED_OUT)은 빠지고, 오늘 것이 먼저
        assertThat(b.reservations().items()).hasSize(2);
        assertThat(b.reservations().items().getFirst().today()).isTrue();
        assertThat(b.rooms().availableNames()).containsExactly("2번 방", "3번 방");
        assertThat(b.rooms().earliestLimit()).isEqualTo("09:30");
        assertThat(b.hints()).isEmpty();
        assertThat(b.afterClass()).as("대표자 예약이 있으면 수업 뒤 칸을 보지 않는다").isNull();
        assertThat(b.suggestions()).isEmpty();
        assertThat(b.text()).isEqualTo("11월 6일 금요일 브리핑이에요. 오늘 수업은 자료구조 09:00–10:15, 운영체제 13:00–14:50 두 개예요. "
                + "마지막 수업은 14:50에 끝나요. 오늘 예약은 1번 방 15:30–17:30이고 대표자예요. 그 뒤로 1건이 더 있어요. "
                + "지금 바로 쓸 수 있는 방은 2번 방, 3번 방 두 개예요.");
    }

    @Test
    @DisplayName("시간표 없음: 넣으라는 안내 + OPEN_SCHEDULE 신호. 예약 없음·빈 방 없음 문장")
    void noScheduleNoReservation() {
        // given
        var tools = fake(Map.of("my_schedule", NO_SCHEDULE, "my_reservations", "{\"reservations\":[]}",
                "room_status", "{\"now\":\"2026-11-06T08:00:00+09:00\",\"availableCount\":0,\"rooms\":[{\"roomId\":1,\"roomName\":\"1번 방\",\"latestEndAt\":null}]}"),
                new ArrayList<>());

        // when
        DailyBriefing.Briefing b = briefing(tools).today(ME);

        // then
        assertThat(b.hints()).containsExactly(ChatResult.HINT_OPEN_SCHEDULE);
        assertThat(b.schedule().classes()).isEmpty();
        assertThat(b.text()).isEqualTo("11월 6일 금요일 브리핑이에요. 아직 넣은 시간표가 없어요. 넣어 두면 수업 끝나는 시각에 맞춰 빈방을 찾아 드려요. "
                + "잡아 둔 예약은 없어요. 지금 바로 쓸 수 있는 방은 없어요.");
    }

    @Test
    @DisplayName("도구 하나가 실패해도 나머지는 보여 준다: 실패한 칸은 null + '불러오지 못했어요'")
    void partialFailure() {
        // given
        var tools = fake(Map.of("my_schedule", FRIDAY, "room_status", STATUS), new ArrayList<>()); // my_reservations 실패

        // when
        DailyBriefing.Briefing b = briefing(tools).today(ME);

        // then
        assertThat(b.reservations().items()).isNull();
        assertThat(b.reservations().note()).isEqualTo("예약을 불러오지 못했어요.");
        assertThat(b.text()).contains("예약을 불러오지 못했어요.").contains("지금 바로 쓸 수 있는 방은 2번 방, 3번 방");
    }

    @Test
    @DisplayName("오늘 예약이 없고 다음 예약이 다른 날이면 '다음 예약은 11월 9일(월) …'로 말한다")
    void nextReservationAnotherDay() {
        // given
        String later = "{\"reservations\":[{\"id\":8,\"roomName\":\"3번 방\",\"startAt\":\"2026-11-09T10:00:00+09:00\","
                + "\"endAt\":\"2026-11-09T11:00:00+09:00\",\"status\":\"BOOKED\",\"owner\":false,\"participantCount\":3}]}";
        var tools = fake(Map.of("my_schedule", FRIDAY, "my_reservations", later, "room_status", STATUS), new ArrayList<>());

        // when
        DailyBriefing.Briefing b = briefing(tools).today(ME);

        // then
        assertThat(b.text()).contains("다음 예약은 11월 9일(월) 3번 방 10:00–11:00이에요.");
    }

    @Test
    @DisplayName("끝난 예약은 빼고, 자정 넘겨 지금 쓰는 예약은 오늘 예약, 오늘 것을 다른 날보다 먼저")
    void endedOvernightAndOrder() {
        // given: 어젯밤 끝난 BOOKED(상태 갱신 늦음), 앞에 온 내일 예약, 어젯밤 23:30 시작해 지금(08:00)도 쓰는 IN_USE
        String rs = "{\"reservations\":["
                + "{\"id\":1,\"roomName\":\"4번 방\",\"startAt\":\"2026-11-05T20:00:00+09:00\",\"endAt\":\"2026-11-05T22:00:00+09:00\",\"status\":\"BOOKED\",\"owner\":true},"
                + "{\"id\":2,\"roomName\":\"3번 방\",\"startAt\":\"2026-11-07T10:00:00+09:00\",\"endAt\":\"2026-11-07T11:00:00+09:00\",\"status\":\"BOOKED\",\"owner\":true},"
                + "{\"id\":3,\"roomName\":\"5번 방\",\"startAt\":\"2026-11-05T23:30:00+09:00\",\"endAt\":\"2026-11-06T09:00:00+09:00\",\"status\":\"IN_USE\",\"owner\":false}]}";
        var tools = fake(Map.of("my_schedule", FRIDAY, "my_reservations", rs, "room_status", STATUS), new ArrayList<>());

        // when
        DailyBriefing.Briefing b = briefing(tools).today(ME);

        // then
        assertThat(b.reservations().items()).extracting(DailyBriefing.ReservationItem::id).containsExactly(3L, 2L);
        assertThat(b.reservations().items().getFirst().today()).isTrue();
        assertThat(b.text()).contains("오늘 예약은 5번 방 23:30–09:00(사용 중)이에요. 그 뒤로 1건이 더 있어요.");
    }

    @Test
    @DisplayName("수업 있고 대표자 예약 없음: searchFromAt(15:30)부터 find_available_rooms를 불러 가장 이른 칸(같은 시작이면 더 긴 쪽, 최대 2시간) + 버튼 문장")
    void afterClassSuggestion() {
        // given: 3번 방 15:30–16:30(1시간), 2번 방 14:00–19:00(15:30부터 2시간 가능), 1번 방 16:00–
        List<String> called = new ArrayList<>();
        var tools = fake(Map.of("my_schedule", FRIDAY, "my_reservations", "{\"reservations\":[]}", "room_status", STATUS,
                "find_available_rooms", FREE), called);

        // when
        DailyBriefing.Briefing b = briefing(tools).today(ME);

        // then
        assertThat(called).contains("find_available_rooms{date=2026-11-06}");
        assertThat(b.afterClass().searchFrom()).isEqualTo("15:30");
        assertThat(b.afterClass().roomName()).isEqualTo("2번 방");
        assertThat(b.afterClass().start()).isEqualTo("15:30");
        assertThat(b.afterClass().end()).isEqualTo("17:30");
        assertThat(b.afterClass().minutes()).isEqualTo(120);
        // 공강 10:15 끝 + 30분 → 11:00 ~ 13:00 (2시간). 4번 방 10:30–12:30 → 11:00부터 1시간 30분
        assertThat(b.freePeriod().periodStart()).isEqualTo("11:00");
        assertThat(b.freePeriod().periodEnd()).isEqualTo("13:00");
        assertThat(b.freePeriod().roomName()).isEqualTo("4번 방");
        assertThat(b.freePeriod().minutes()).isEqualTo(90);
        assertThat(b.suggestions()).containsExactly("15:30 2번 방 2시간 잡아 줘", "11:00 4번 방 1시간 30분 잡아 줘");
        assertThat(b.text()).contains("마지막 수업은 14:50에 끝나요. 공강 11:00–13:00에는 4번 방을 11:00부터 1시간 30분 쓸 수 있어요. "
                + "수업 끝나고 15:30부터 2번 방을 2시간 쓸 수 있어요. 잡아 둔 예약은 없어요.");
    }

    @Test
    @DisplayName("수업이 이미 끝난 뒤(18:10)에 열면 지금 칸(18:30)부터 본다. 같은 시작이면 더 긴 쪽(1번 방 2시간 > 2번 방 30분)")
    void afterClassFromNow() {
        // given: 18:10 서울. 2번 방은 –19:00라 30분, 1번 방은 –22:00라 2시간 → 1번 방
        Clock late = Clock.fixed(Instant.parse("2026-11-06T09:10:00Z"), ZoneId.of("UTC"));
        List<String> called = new ArrayList<>();
        var tools = fake(Map.of("my_schedule", FRIDAY, "my_reservations", "{\"reservations\":[]}", "room_status", STATUS,
                "find_available_rooms", FREE), called);

        // when
        DailyBriefing.Briefing b = briefing(tools, late).today(ME);

        // then
        assertThat(called).contains("find_available_rooms{date=2026-11-06}");
        assertThat(b.afterClass().searchFrom()).isEqualTo("18:30");
        assertThat(b.afterClass().start()).isEqualTo("18:30");
        assertThat(b.freePeriod()).as("공강은 이미 지났다").isNull();
        assertThat(b.afterClass().minutes()).isEqualTo(120);
        assertThat(b.afterClass().roomName()).isEqualTo("1번 방");
        assertThat(b.suggestions()).containsExactly("18:30 1번 방 2시간 잡아 줘");
        assertThat(DailyBriefing.lengthText(90)).isEqualTo("1시간 30분");
        assertThat(DailyBriefing.lengthText(30)).isEqualTo("30분");
    }

    @Test
    @DisplayName("공강: 1시간보다 짧은 공강은 안 보고, 공강 중간(11:40)에 열면 지금 칸(12:00)부터 남은 만큼만")
    void freePeriodShortOrInProgress() {
        // given: 수업 09:00–10:15, 11:00–12:00(공강 10:45→11:00~11:00 = 0), 13:00–14:50(공강 12:30~13:00 = 30분) → 공강 없음
        String tight = """
                {"date":"2026-11-06","dayOfWeek":"금","code":null,
                 "classes":[{"name":"A","startAt":"2026-11-06T09:00:00+09:00","endAt":"2026-11-06T10:15:00+09:00"},
                            {"name":"B","startAt":"2026-11-06T11:00:00+09:00","endAt":"2026-11-06T12:00:00+09:00"},
                            {"name":"C","startAt":"2026-11-06T13:00:00+09:00","endAt":"2026-11-06T14:50:00+09:00"}],
                 "lastClassEndAt":"2026-11-06T14:50:00+09:00","searchFromAt":"2026-11-06T15:30:00+09:00","bufferMinutes":30,"note":null}""";
        var none = fake(Map.of("my_schedule", tight, "my_reservations", "{\"reservations\":[]}", "room_status", STATUS,
                "find_available_rooms", FREE), new ArrayList<>());
        Clock midGap = Clock.fixed(Instant.parse("2026-11-06T02:40:00Z"), ZoneId.of("UTC")); // 11:40 서울
        var inProgress = fake(Map.of("my_schedule", FRIDAY, "my_reservations", "{\"reservations\":[]}", "room_status", STATUS,
                "find_available_rooms", FREE), new ArrayList<>());

        // when
        DailyBriefing.Briefing n = briefing(none).today(ME);
        DailyBriefing.Briefing p = briefing(inProgress, midGap).today(ME);

        // then
        assertThat(n.freePeriod()).isNull();
        assertThat(n.afterClass().roomName()).isEqualTo("2번 방");
        assertThat(p.freePeriod().periodStart()).isEqualTo("12:00");
        assertThat(p.freePeriod().start()).isEqualTo("12:00");
        assertThat(p.freePeriod().minutes()).isEqualTo(30); // 4번 방 –12:30
        assertThat(p.suggestions()).containsExactly("15:30 2번 방 2시간 잡아 줘", "12:00 4번 방 30분 잡아 줘");
    }

    @Test
    @DisplayName("빈방 조회 실패·빈 결과: 다른 칸은 그대로, 문장만 '확인하지 못했어요'/'쓸 수 있는 방은 없어요'. 시간표 없으면 아예 안 본다")
    void afterClassNoneOrFailed() {
        // given: 조회 실패
        List<String> called = new ArrayList<>();
        var failed = fake(Map.of("my_schedule", FRIDAY, "my_reservations", "{\"reservations\":[]}", "room_status", STATUS), called);
        var none = fake(Map.of("my_schedule", FRIDAY, "my_reservations", "{\"reservations\":[]}", "room_status", STATUS,
                "find_available_rooms", "{\"date\":\"2026-11-06\",\"rooms\":[],\"note\":\"없어요\"}"), new ArrayList<>());
        List<String> noScheduleCalls = new ArrayList<>();
        var noSchedule = fake(Map.of("my_schedule", NO_SCHEDULE, "my_reservations", "{\"reservations\":[]}", "room_status", STATUS),
                noScheduleCalls);

        // when
        DailyBriefing.Briefing f = briefing(failed).today(ME);
        DailyBriefing.Briefing n = briefing(none).today(ME);
        briefing(noSchedule).today(ME);

        // then
        assertThat(f.afterClass().note()).isEqualTo(DailyBriefing.AFTER_CLASS_FAILED);
        assertThat(f.suggestions()).isEmpty();
        assertThat(f.text()).contains("수업 뒤 빈방을 확인하지 못했어요. 잡아 둔 예약은 없어요.");
        assertThat(n.afterClass().note()).isEqualTo(DailyBriefing.AFTER_CLASS_NONE);
        assertThat(n.text()).contains("수업 끝나고 오늘 쓸 수 있는 방은 없어요.");
        assertThat(noScheduleCalls).noneMatch(c -> c.startsWith("find_available_rooms"));
    }

    static final String WEATHER = """
            {"date":"2026-11-06","place":"광주 조선대","summary":"비","minTemperature":8.1,"maxTemperature":15.2,
             "rainChance":70,"umbrella":true,"nowTemperature":9.0,"nowSummary":"흐림","note":"Open-Meteo 예보 기준이에요."}""";
    static final String SHUTTLE = """
            {"date":"2026-11-06","dayOfWeek":"금","after":"08:00","code":null,
             "routes":[{"name":"사회과학관 버스","stop":"설립기념탑 → 미대 → 사회과학관 → 설립기념탑","next":["08:30","08:40"],"last":"17:00","running":null},
                       {"name":"글로벌하우스 버스","stop":"글로벌하우스 → 해오름관","next":["09:20"],"last":"18:30","running":null}],
             "basis":"학교가 정한 시간표 기준이에요.","note":null}""";

    @Test
    @DisplayName("아침 브리핑(11차 회의): 날씨는 맨 앞, 다음 셔틀은 맨 뒤에 한 줄씩. 우산은 비 올 확률이 높을 때만")
    void campusLines() {
        // given
        List<String> called = new ArrayList<>();
        var tools = fake(Map.of("my_schedule", NO_SCHEDULE, "my_reservations", "{\"reservations\":[]}", "room_status", STATUS,
                "campus_weather", WEATHER, "campus_shuttle", SHUTTLE), called);

        // when
        DailyBriefing.Briefing b = new DailyBriefing(tools, CLOCK, JsonMapper.builder().build(),
                new AgentProperties.Rules(30, 2, 7), true).today(ME);

        // then
        assertThat(called).contains("campus_weather{}", "campus_shuttle{}");
        assertThat(b.campus().weather().umbrella()).isTrue();
        assertThat(b.campus().shuttle().next()).isEqualTo("08:30");
        assertThat(b.text()).startsWith("11월 6일 금요일 브리핑이에요. 오늘 광주 조선대는 비, 8~15도예요. 비 올 확률 70%라 우산을 챙기세요. ");
        assertThat(b.text()).endsWith("다음 교내 셔틀은 사회과학관 버스 08:30이에요(시간표 기준).");
    }

    @Test
    @DisplayName("아침 브리핑: 지금 수시 운행 중인 노선이 있으면 출발 시각 대신 운행 시간대를 알려 준다")
    void shuttleRunning() {
        // given: 본관 순환 버스는 07:40~17:00 수시 운행(학교 공지), 사회과학관 버스는 08:30 출발
        String shuttle = """
                {"date":"2026-11-06","code":null,"routes":[
                 {"name":"본관 순환 버스","stop":"설립기념탑 → 미대","next":["17:00"],"last":"23:00","running":"07:40~17:00 수시 운행(5~10분 간격)"},
                 {"name":"사회과학관 버스","stop":"설립기념탑 → 미대 → 사회과학관","next":["08:30"],"last":"17:00","running":null}],
                 "basis":"시간표 기준","note":null}""";
        var tools = fake(Map.of("my_schedule", NO_SCHEDULE, "my_reservations", "{\"reservations\":[]}", "room_status", STATUS,
                "campus_shuttle", shuttle), new ArrayList<>());

        // when
        DailyBriefing.Briefing b = new DailyBriefing(tools, CLOCK, JsonMapper.builder().build(),
                new AgentProperties.Rules(30, 2, 7), true).today(ME);

        // then
        assertThat(b.campus().shuttle().running()).isEqualTo("07:40~17:00 수시 운행(5~10분 간격)");
        assertThat(b.text()).endsWith("지금 본관 순환 버스는 07:40~17:00 수시 운행(5~10분 간격)이에요(시간표 기준).");
    }

    @Test
    @DisplayName("아침 브리핑: 날씨가 실패하고 셔틀 시간표가 없으면 두 줄 다 빼고 나머지는 그대로")
    void campusMissing() {
        // given
        var tools = fake(Map.of("my_schedule", NO_SCHEDULE, "my_reservations", "{\"reservations\":[]}", "room_status", STATUS,
                "campus_shuttle", "{\"code\":\"NO_TIMETABLE\",\"routes\":[],\"note\":\"없어요\"}"), new ArrayList<>());

        // when
        DailyBriefing.Briefing b = new DailyBriefing(tools, CLOCK, JsonMapper.builder().build(),
                new AgentProperties.Rules(30, 2, 7), true).today(ME);

        // then
        assertThat(b.campus().weather()).isNull();
        assertThat(b.campus().shuttle()).isNull();
        assertThat(b.text()).doesNotContain("셔틀", "우산", "광주").endsWith("지금 바로 쓸 수 있는 방은 2번 방, 3번 방 두 개예요.");
    }
}
