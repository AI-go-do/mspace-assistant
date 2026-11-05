package kr.ac.chosun.mspace.assistant.mcp.schedule.application;

import kr.ac.chosun.mspace.assistant.mcp.schedule.adapter.out.memory.InMemoryScheduleRepository;
import kr.ac.chosun.mspace.assistant.mcp.schedule.adapter.out.redis.RedisScheduleRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScheduleProperties.class)
class ScheduleConfig {

    /**
     * store=redis면 Redis, 아니면 메모리. Redis 템플릿은 starter가 만들고 연결은 처음 쓸 때 맺는다
     * (memory 모드에서는 쓰지 않으니 Redis 없이 뜬다)
     */
    @Bean
    ScheduleRepository scheduleRepository(ScheduleProperties props, ObjectProvider<StringRedisTemplate> redis) {
        if ("redis".equals(props.store())) {
            return new RedisScheduleRepository(redis.getObject());
        }
        return new InMemoryScheduleRepository();
    }
}
