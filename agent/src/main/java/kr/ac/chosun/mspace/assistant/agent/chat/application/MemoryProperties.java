package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 대화 맥락 보관 설정 (application.yml의 mspace.agent.memory).
 *
 * @param enabled  false면 저장하지 않고 화면이 보낸 history만 쓴다 (예전 방식)
 * @param ttl      마지막 대화 뒤 이 시간이 지나면 잊는다
 * @param maxChars 보관할 대화 글자 수 상한. 넘으면 오래된 차례부터 버린다 (도구 결과가 커서 모델 비용을 막는다)
 */
@ConfigurationProperties("mspace.agent.memory")
public record MemoryProperties(boolean enabled, Duration ttl, int maxChars) {

    public MemoryProperties {
        ttl = ttl == null ? Duration.ofHours(24) : ttl;
        maxChars = maxChars <= 0 ? 20_000 : maxChars;
    }
}
