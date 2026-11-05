package kr.ac.chosun.mspace.assistant.mcp.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.schedule.adapter.out.redis.RedisScheduleRepository;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.ScheduleRepository;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.Timetable;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.WeeklyClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Redis 서버 없이 템플릿만 가짜로 둔다. 키·TTL 없음·왕복·장애 때 동작만 본다 */
class RedisScheduleRepositoryTest {

    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final RedisScheduleRepository repository = new RedisScheduleRepository(redis);

    final Timetable timetable = new Timetable(
            List.of(new WeeklyClass(DayOfWeek.FRIDAY, LocalTime.of(13, 0), LocalTime.of(14, 50), "자료구조")),
            OffsetDateTime.parse("2026-11-05T18:40:00+09:00"));

    @Test
    @DisplayName("agent:schedule:<학생 키>에 TTL 없이 저장하고, 읽으면 서울 시간 그대로 돌아온다")
    void roundTrip() {
        // given
        when(redis.opsForValue()).thenReturn(values);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);

        // when
        repository.save("m:42", timetable);
        verify(values).set(eq("agent:schedule:m:42"), json.capture());
        when(values.get("agent:schedule:m:42")).thenReturn(json.getValue());

        // then
        Timetable read = repository.find("m:42").orElseThrow();
        assertThat(read.classes()).isEqualTo(timetable.classes());
        assertThat(read.updatedAt().toString()).isEqualTo("2026-11-05T18:40+09:00");
    }

    @Test
    @DisplayName("Redis가 죽어 있으면 없는 척하지 않고 SCHEDULE_UNAVAILABLE")
    void downIsUnavailable() {
        // given
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        // then
        assertThatThrownBy(() -> repository.find("m:42")).isInstanceOf(ScheduleRepository.Unavailable.class)
                .hasMessageContaining("불러오지 못했어요");
    }

    @Test
    @DisplayName("값이 깨져 있으면 없는 것으로 본다 (다시 넣으면 고쳐진다)")
    void brokenValueIsEmpty() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("agent:schedule:m:42")).thenReturn("{not json");

        assertThat(repository.find("m:42")).isEmpty();
    }

    @Test
    @DisplayName("지우기는 그 학생 키만 지운다")
    void delete() {
        repository.delete("m:42");

        verify(redis).delete("agent:schedule:m:42");
    }
}
