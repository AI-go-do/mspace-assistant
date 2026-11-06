package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.MemoryProperties;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.ValueOperations;

/** Redis 서버 없이 템플릿만 가짜로 둔다. 키·TTL·장애 때 동작만 본다 (연결 자체는 Spring Data가 책임) */
class RedisConversationMemoryTest {

    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final MemoryProperties props = new MemoryProperties(true, "redis", Duration.ofHours(24), 0);
    final RedisConversationMemory memory = new RedisConversationMemory(redis, props);

    @Test
    @DisplayName("키는 agent:conv:<학생 키>, 저장은 TTL과 함께, 읽으면 같은 대화가 돌아온다")
    void saveAndLoad() {
        // given
        when(redis.opsForValue()).thenReturn(values);
        List<LlmMessage> conversation = List.of(new LlmMessage.User("안녕"), new LlmMessage.Assistant("네", List.of()));
        when(values.get("agent:conv:m:42")).thenReturn(ConversationJson.write(conversation));

        // when
        memory.save("m:42", conversation);
        List<LlmMessage> back = memory.load("m:42");
        memory.clear("m:42");

        // then
        verify(values).set(eq("agent:conv:m:42"), anyString(), eq(Duration.ofHours(24)));
        assertThat(back).isEqualTo(conversation);
        verify(redis).delete(List.of("agent:conv:m:42", "agent:conv-notes:m:42"));
    }

    @Test
    @DisplayName("미룬 글: agent:conv-notes:<학생 키>에 RPUSH + TTL, 꺼낼 때는 LPOP으로 한꺼번에(쌓인 순서), 없으면 빈 목록")
    void notes() {
        // given
        @SuppressWarnings("unchecked")
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        List<LlmMessage> first = List.of(new LlmMessage.User("[화면] 확인"), new LlmMessage.Assistant("예약했어요. (예약 7번, BOOKED)", List.of()));
        List<LlmMessage> second = List.of(new LlmMessage.User("[화면] 확인"), new LlmMessage.Assistant("취소했어요. (예약 7번, CANCELED)", List.of()));
        when(lists.leftPop(eq("agent:conv-notes:m:42"), anyLong()))
                .thenReturn(List.of(ConversationJson.write(first), ConversationJson.write(second)))
                .thenReturn(null);

        // when
        memory.addNote("m:42", first);
        List<LlmMessage> taken = memory.takeNotes("m:42");
        List<LlmMessage> again = memory.takeNotes("m:42");

        // then
        verify(lists).rightPush("agent:conv-notes:m:42", ConversationJson.write(first));
        verify(redis).expire("agent:conv-notes:m:42", Duration.ofHours(24));
        assertThat(taken).hasSize(4).containsSequence(first).containsSequence(second);
        assertThat(again).isEmpty();
    }

    @Test
    @DisplayName("Redis가 죽어 있으면 읽기·저장은 기억 없이 넘어가고, 지우기만 Unavailable로 알린다")
    void degradesWhenRedisDown() {
        // given
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString(), eq(Duration.ofHours(24)));
        when(redis.delete(org.mockito.ArgumentMatchers.anyCollection())).thenThrow(new RedisConnectionFailureException("down"));
        @SuppressWarnings("unchecked")
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        when(lists.rightPush(anyString(), anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(lists.leftPop(anyString(), anyLong())).thenThrow(new RedisConnectionFailureException("down"));

        // when / then
        assertThat(memory.load("m:1")).isEmpty();
        memory.save("m:1", List.of(new LlmMessage.User("안녕")));
        assertThatThrownBy(() -> memory.clear("m:1")).isInstanceOf(ConversationMemory.Unavailable.class);
        memory.addNote("m:1", List.of(new LlmMessage.User("[화면] 확인")));
        assertThat(memory.takeNotes("m:1")).isEmpty();
    }
}
