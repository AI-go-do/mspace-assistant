package kr.ac.chosun.mspace.assistant.mcp.common;

import java.util.LinkedHashMap;
import java.util.Map;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring AI 기본 설정의 Streamable HTTP 전송은 HTTP 헤더를 도구까지 넘기지 않는다(빈 문맥).
 * 같은 빈을 여기서 직접 만들어, 허용한 헤더만 UserCredential로 담아 도구에 넘긴다.
 * 기본 빈은 @ConditionalOnMissingBean이라 이 빈이 있으면 만들어지지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoreProperties.class)
class McpTransportConfig {

    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            JsonMapper jsonMapper, McpServerStreamableHttpProperties props, CoreProperties core) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(props.getMcpEndpoint())
                .keepAliveInterval(props.getKeepAliveInterval())
                .disallowDelete(props.isDisallowDelete())
                .contextExtractor(request -> extract(request, core))
                .build();
    }

    private static McpTransportContext extract(ServerRequest request, CoreProperties core) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : core.forwardHeaders()) {
            String value = request.headers().firstHeader(name);
            if (value != null && !value.isBlank()) {
                headers.put(name, value);
            }
        }
        return McpTransportContext.create(Map.of(UserCredential.CONTEXT_KEY, new UserCredential(headers)));
    }
}
