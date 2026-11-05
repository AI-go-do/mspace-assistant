package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.Clock;
import kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory.InMemoryConversationMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentProperties.class, ChatJobProperties.class, MemoryProperties.class})
class AgentConfig {

    /** "내일"을 날짜로 바꾸는 기준 시각. 테스트는 고정 시계로 바꿔 끼운다 */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** 앱이 꺼질 때 돌던 채팅 스레드도 멈춘다 (close) */
    @Bean
    ChatJobs chatJobs(ChatJobProperties props, Clock clock) {
        return new ChatJobs(props, clock);
    }

    /**
     * 대화 맥락 보관. 기본은 메모리(agent 1대). 끄면 화면이 보낸 history만 쓴다.
     * Redis·DB 어댑터는 저장소가 정해지면 여기서 바꿔 끼운다 (설계 4장)
     */
    @Bean
    ConversationMemory conversationMemory(MemoryProperties props, Clock clock) {
        return props.enabled() ? new InMemoryConversationMemory(props, clock) : ConversationMemory.NONE;
    }
}
