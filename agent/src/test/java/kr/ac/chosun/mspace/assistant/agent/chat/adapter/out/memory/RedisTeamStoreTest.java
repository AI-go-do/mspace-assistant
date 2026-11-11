package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisTeamStoreTest {

    @SuppressWarnings("unchecked")
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    @DisplayName("agent:teams:<학생 키>에 팀 목록 JSON을 저장하고 그대로 읽는다. 깨진 값·Redis 장애 읽기는 빈 목록")
    void roundTrip() {
        // given
        when(redis.opsForValue()).thenReturn(values);
        RedisTeamStore store = new RedisTeamStore(redis);
        List<Team> teams = List.of(new Team("팀 A", List.of(new Team.Member("99999002", "김*영"))));

        // when
        store.save("m:1", teams);
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("agent:teams:m:1"), saved.capture());
        when(values.get("agent:teams:m:1")).thenReturn(saved.getValue());
        when(values.get("agent:teams:m:2")).thenReturn("{broken");

        // then
        assertThat(store.load("m:1")).isEqualTo(teams);
        assertThat(store.load("m:2")).isEmpty();
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(store.load("m:1")).isEmpty();
    }

    @Test
    @DisplayName("저장 실패는 Unavailable로 알린다 (저장된 척하지 않음)")
    void saveFails() {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString());
        assertThatThrownBy(() -> new RedisTeamStore(redis).save("m:1", List.of())).isInstanceOf(ConversationMemory.Unavailable.class);
    }

    @Test
    @DisplayName("바꾸기 전 읽기(loadStrict)는 못 읽으면 빈 목록이 아니라 Unavailable (빈 목록으로 덮어써 팀을 잃지 않게)")
    void strictLoadFails() {
        // given
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("agent:teams:m:2")).thenReturn("{broken");
        RedisTeamStore store = new RedisTeamStore(redis);

        // when·then
        assertThatThrownBy(() -> store.loadStrict("m:2")).isInstanceOf(ConversationMemory.Unavailable.class);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThatThrownBy(() -> store.loadStrict("m:1")).isInstanceOf(ConversationMemory.Unavailable.class);
    }
}
