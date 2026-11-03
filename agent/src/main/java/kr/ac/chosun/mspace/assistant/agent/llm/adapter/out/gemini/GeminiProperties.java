package kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini 연결 설정 (application.yml의 mspace.llm.gemini). 키는 .env·GitHub Secrets에만 둔다.
 *
 * @param model 기본 gemini-2.5-flash. 무료 등급 한도는 모델마다 달라서 시연 전에 확인 필요
 */
@ConfigurationProperties("mspace.llm.gemini")
public record GeminiProperties(String baseUrl, String model, String apiKey, Duration timeout) {

    public GeminiProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://generativelanguage.googleapis.com" : baseUrl;
        model = model == null || model.isBlank() ? "gemini-2.5-flash" : model;
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
    }
}
