package kr.ac.chosun.mspace.assistant.mcp.confirm.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConfirmProperties.class)
class ConfirmConfig {

    /** 만료 계산용 시계. 테스트는 @Primary 시계로 바꿔 끼운다 */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
