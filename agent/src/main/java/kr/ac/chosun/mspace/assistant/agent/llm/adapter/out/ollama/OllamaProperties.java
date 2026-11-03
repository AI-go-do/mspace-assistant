package kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.ollama;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로컬 Ollama 연결 설정 (mspace.llm.ollama). 키가 필요 없다.
 * 어디서 돌릴지는 아직 정하지 않았다: Jetson 보드 B는 후보 중 하나 (팀 결정 대기 Q-10).
 *
 * @param model          도구 호출을 지원하는 모델이어야 한다. 기본 qwen2.5:7b (확인 필요: 돌릴 기기 메모리에 맞는지 #9에서 측정)
 * @param timeout        응답 읽기 최대 시간. 대화 전체 상한(mspace.agent.chat-timeout 45초)·nginx 60초 안에 들게 40초
 * @param connectTimeout 연결 최대 시간. 서버가 꺼져 있으면 빨리 알리게 짧게 둔다
 */
@ConfigurationProperties("mspace.llm.ollama")
public record OllamaProperties(String baseUrl, String model, Duration timeout, Duration connectTimeout) {

    public OllamaProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434" : baseUrl;
        model = model == null || model.isBlank() ? "qwen2.5:7b" : model;
        timeout = timeout == null ? Duration.ofSeconds(40) : timeout;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
    }
}
