package kr.ac.chosun.mspace.assistant.agent.llm;

import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini.GeminiLlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini.GeminiProperties;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.ollama.OllamaLlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.ollama.OllamaProperties;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** mspace.llm.provider 값 하나로 모델을 바꾼다. gemini(기본, 시연) | ollama(학교 도입) */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GeminiProperties.class, OllamaProperties.class})
class LlmConfig {

    @Bean
    @ConditionalOnProperty(name = "mspace.llm.provider", havingValue = "gemini", matchIfMissing = true)
    LlmClient geminiLlmClient(GeminiProperties props, JsonMapper json) {
        return new GeminiLlmClient(props, json);
    }

    @Bean
    @ConditionalOnProperty(name = "mspace.llm.provider", havingValue = "ollama")
    LlmClient ollamaLlmClient(OllamaProperties props) {
        return new OllamaLlmClient(props);
    }
}
