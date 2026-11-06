package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.Persona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisPersonaStoreTest {

    @SuppressWarnings("unchecked")
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    @DisplayName("agent:persona:<학생 키>에 성향 이름을 TTL 없이 저장하고, 없거나 이상한 값·Redis 장애면 기본 성향")
    void saveAndLoad() {
        when(redis.opsForValue()).thenReturn(values);
        RedisPersonaStore store = new RedisPersonaStore(redis);

        store.save("m:1", Persona.NAGGING);
        verify(values).set("agent:persona:m:1", "NAGGING");

        when(values.get("agent:persona:m:1")).thenReturn("NAGGING");
        when(values.get("agent:persona:m:2")).thenReturn(null);
        when(values.get("agent:persona:m:3")).thenReturn("garbage");
        assertThat(store.load("m:1")).isEqualTo(Persona.NAGGING);
        assertThat(store.load("m:2")).isEqualTo(Persona.ACTIVE);
        assertThat(store.load("m:3")).isEqualTo(Persona.ACTIVE);

        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(store.load("m:1")).isEqualTo(Persona.ACTIVE);
    }

    @Test
    @DisplayName("저장이 실패하면 바뀐 척하지 않고 Unavailable(503)")
    void saveFails() {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString());

        assertThatThrownBy(() -> new RedisPersonaStore(redis).save("m:1", Persona.QUIET))
                .isInstanceOf(ConversationMemory.Unavailable.class);
    }
}
