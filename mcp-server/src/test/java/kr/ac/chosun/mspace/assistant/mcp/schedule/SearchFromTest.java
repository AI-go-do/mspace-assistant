package kr.ac.chosun.mspace.assistant.mcp.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import kr.ac.chosun.mspace.assistant.mcp.schedule.adapter.out.memory.InMemoryScheduleRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;

/** "수업 끝나고"의 시작 시각 계산: 끝 + 여유를 다음 30분 칸으로 올린다 (기획서 예 14:50 → 15:30) */
class SearchFromTest {

    @DisplayName("끝 + 여유 30분을 다음 30분 칸으로 올린다")
    @ParameterizedTest(name = "{0} 끝 → {1}부터")
    @CsvSource({"14:50,15:30", "15:00,15:30", "15:01,16:00", "17:45,18:30", "23:40,00:30"})
    void roundsUp(String end, String expected) {
        // given
        var schedule = new MySchedule(new InMemoryScheduleRepository(), new ScheduleProperties("memory", 30, 30, 0, 0, 7, null),
                Clock.systemUTC());

        // when
        OffsetDateTime from = schedule.searchFrom(OffsetDateTime.parse("2026-11-06T" + end + ":00+09:00"));

        // then
        assertThat(from.toLocalTime().toString()).isEqualTo(expected);
    }

    @DisplayName("여유를 0분으로 바꾸면 끝 시각을 칸으로만 올린다 (설정값)")
    @ParameterizedTest(name = "{0} 끝 → {1}부터")
    @CsvSource({"14:50,15:00", "15:00,15:00"})
    void zeroBuffer(String end, String expected) {
        // given
        var schedule = new MySchedule(new InMemoryScheduleRepository(), new ScheduleProperties("memory", 0, 30, 0, 0, 7, null),
                Clock.systemUTC());

        // when
        OffsetDateTime from = schedule.searchFrom(OffsetDateTime.parse("2026-11-06T" + end + ":00+09:00"));

        // then
        assertThat(from.toLocalTime().toString()).isEqualTo(expected);
    }
}
