package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.Clock;
import kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory.InMemoryConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory.InMemoryPersonaStore;
import kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory.RedisConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory.RedisPersonaStore;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ImageReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentProperties.class, ChatJobProperties.class, MemoryProperties.class})
class AgentConfig {

    /** "내일"을 날짜로 바꾸는 기준 시각. 테스트는 고정 시계로 바꿔 끼운다 */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** 앱이 꺼질 때 돌던 채팅 스레드도 멈춘다 (close) */
    /** 시간표 캡처 읽기 (설계 5장). 모델 선택(gemini/ollama)은 LlmConfig의 ImageReader 빈이 정한다 */
    @Bean
    ReadTimetableImage readTimetableImage(ImageReader reader, JsonMapper json) {
        return new ReadTimetableImage(reader, json);
    }

    @Bean
    ChatJobs chatJobs(ChatJobProperties props, Clock clock) {
        return new ChatJobs(props, clock);
    }

    /**
     * 대화 맥락 보관 (설계 4장). store=redis면 Redis(11/5 결정), 아니면 메모리(agent 1대). 끄면 화면이 보낸 history만 쓴다.
     * Redis 템플릿은 starter가 만들고, 연결은 처음 쓸 때 맺는다(memory 모드에서는 쓰지 않으니 Redis 없이 뜬다)
     */
    @Bean
    ConversationMemory conversationMemory(MemoryProperties props, Clock clock, ObjectProvider<StringRedisTemplate> redis) {
        if (!props.enabled()) {
            return ConversationMemory.NONE;
        }
        if ("redis".equals(props.store())) {
            return new RedisConversationMemory(redis.getObject(), props);
        }
        return new InMemoryConversationMemory(props, clock);
    }

    /** 비서 성향 보관 (설계 5.v). 대화 맥락과 같은 저장소를 쓴다: redis면 Redis(TTL 없음), 아니면 메모리 */
    @Bean
    PersonaStore personaStore(MemoryProperties props, ObjectProvider<StringRedisTemplate> redis) {
        if (props.enabled() && "redis".equals(props.store())) {
            return new RedisPersonaStore(redis.getObject());
        }
        return new InMemoryPersonaStore();
    }
}
