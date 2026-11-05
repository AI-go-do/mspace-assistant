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
            }
        };
    }

    static DailyBriefing briefing(ToolGateway tools) {
        return new DailyBriefing(tools, CLOCK, JsonMapper.builder().build());
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
        assertThat(b.text()).isEqualTo("11월 6일 금요일 브리핑이에요. 오늘 수업은 자료구조 09:00–10:15, 운영체제 13:00–14:50 두 개예요. "
                + "마지막 수업은 14:50에 끝나요. 오늘 예약은 1번 방 15:30–17:30이고 대표자예요. 그 뒤로 1건이 더 있어요. "
                + "지금 바로 쓸 수 있는 방은 2번 방, 3번 방 두 개이에요.");
    }

    @Test
    @DisplayName("시간표 없음: 넣으라는 안내 + OPEN_SCHEDULE 신호. 예약 없음·빈 방 없음 문장")
    void noScheduleNoReservation() {
        var tools = fake(Map.of("my_schedule", NO_SCHEDULE, "my_reservations", "{\"reservations\":[]}",
                "room_status", "{\"now\":\"2026-11-06T08:00:00+09:00\",\"availableCount\":0,\"rooms\":[{\"roomId\":1,\"roomName\":\"1번 방\",\"latestEndAt\":null}]}"),
                new ArrayList<>());

        DailyBriefing.Briefing b = briefing(tools).today(ME);

        assertThat(b.hints()).containsExactly(ChatResult.HINT_OPEN_SCHEDULE);
        assertThat(b.schedule().classes()).isEmpty();
        assertThat(b.text()).isEqualTo("11월 6일 금요일 브리핑이에요. 아직 넣은 시간표가 없어요. 넣어 두면 수업 끝나는 시각에 맞춰 빈방을 찾아 드려요. "
                + "잡아 둔 예약은 없어요. 지금 바로 쓸 수 있는 방은 없어요.");
    }

    @Test
    @DisplayName("도구 하나가 실패해도 나머지는 보여 준다: 실패한 칸은 null + '불러오지 못했어요'")
    void partialFailure() {
        var tools = fake(Map.of("my_schedule", FRIDAY, "room_status", STATUS), new ArrayList<>()); // my_reservations 실패

        DailyBriefing.Briefing b = briefing(tools).today(ME);

        assertThat(b.reservations().items()).isNull();
        assertThat(b.reservations().note()).isEqualTo("예약을 불러오지 못했어요.");
        assertThat(b.text()).contains("예약을 불러오지 못했어요.").contains("지금 바로 쓸 수 있는 방은 2번 방, 3번 방");
    }

    @Test
    @DisplayName("오늘 예약이 없고 다음 예약이 다른 날이면 '다음 예약은 11월 9일(월) …'로 말한다")
    void nextReservationAnotherDay() {
        String later = "{\"reservations\":[{\"id\":8,\"roomName\":\"3번 방\",\"startAt\":\"2026-11-09T10:00:00+09:00\","
                + "\"endAt\":\"2026-11-09T11:00:00+09:00\",\"status\":\"BOOKED\",\"owner\":false,\"participantCount\":3}]}";
        var tools = fake(Map.of("my_schedule", FRIDAY, "my_reservations", later, "room_status", STATUS), new ArrayList<>());

        DailyBriefing.Briefing b = briefing(tools).today(ME);

        assertThat(b.text()).contains("다음 예약은 11월 9일(월) 3번 방 10:00–11:00이에요.");
    }
}
