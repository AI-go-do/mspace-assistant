package kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.ollama;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로컬 Ollama 연결 설정 (mspace.llm.ollama). 학교 도입 시 Jetson 보드 B에서 돌린다. 키가 필요 없다.
 *
 * @param model 도구 호출을 지원하는 모델이어야 한다. 기본 qwen2.5:7b (확인 필요: 보드 메모리에 맞는지 #9에서 측정)
 * @param timeout 작은 보드는 느려서 Gemini보다 길게 둔다
 */
@ConfigurationProperties("mspace.llm.ollama")
public record OllamaProperties(String baseUrl, String model, Duration timeout) {

    public OllamaProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434" : baseUrl;
        model = model == null || model.isBlank() ? "qwen2.5:7b" : model;
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }
}
