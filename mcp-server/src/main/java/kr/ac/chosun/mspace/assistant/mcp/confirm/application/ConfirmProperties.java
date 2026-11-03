package kr.ac.chosun.mspace.assistant.mcp.confirm.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 확인 토큰 설정 (application.yml의 mspace.confirm).
 *
 * @param ttl 확인 토큰이 살아 있는 시간. 지나면 "다시 말해 주세요"
 */
@ConfigurationProperties("mspace.confirm")
public record ConfirmProperties(Duration ttl) {

    public ConfirmProperties {
        ttl = ttl == null ? Duration.ofMinutes(2) : ttl;
    }
}
