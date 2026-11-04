package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentProperties.class, ChatJobProperties.class})
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
}
