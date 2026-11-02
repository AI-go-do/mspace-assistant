package kr.ac.chosun.mspace.assistant.agent.llm;

import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini.GeminiLlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini.GeminiProperties;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** mspace.llm.provider 값 하나로 모델을 바꾼다. 지금은 gemini(기본). ollama는 #14 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeminiProperties.class)
class LlmConfig {

    @Bean
    @ConditionalOnProperty(name = "mspace.llm.provider", havingValue = "gemini", matchIfMissing = true)
    LlmClient geminiLlmClient(GeminiProperties props, JsonMapper json) {
        return new GeminiLlmClient(props, json);
    }
}
