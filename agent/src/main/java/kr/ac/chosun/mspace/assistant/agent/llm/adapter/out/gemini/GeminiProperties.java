package kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini 연결 설정 (application.yml의 mspace.llm.gemini). 키는 .env·GitHub Secrets에만 둔다.
 *
 * @param model 기본 gemini-3.5-flash-lite. 2.5 계열은 새 프로젝트에서 못 쓸 수 있어 바꿈(공식 deprecations 문서, 11/5 확인). 무료 한도는 AI Studio에서 프로젝트별로 확인
 */
@ConfigurationProperties("mspace.llm.gemini")
public record GeminiProperties(String baseUrl, String model, String apiKey, Duration timeout) {

    public GeminiProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://generativelanguage.googleapis.com" : baseUrl;
        model = model == null || model.isBlank() ? "gemini-3.5-flash-lite" : model;
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
    }
}
