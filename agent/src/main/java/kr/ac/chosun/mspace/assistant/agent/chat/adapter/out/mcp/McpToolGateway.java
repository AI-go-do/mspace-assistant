package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ToolGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * mcp-server에 공식 MCP Java 클라이언트(Streamable HTTP)로 붙는다. 학생 헤더를 모든 요청에 싣는다.
 * 대화 한 번 = 세션 하나 (연결·initialize·tools/list 한 번). 학생마다 헤더가 달라서 세션을 공유하지 않는다.
 */
@Component
class McpToolGateway implements ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(McpToolGateway.class);

    private final AgentProperties props;

    McpToolGateway(AgentProperties props) {
        this.props = props;
    }

    @Override
    public Session open(UserHeaders headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder();
        headers.values().forEach(request::header);
        var transport = HttpClientStreamableHttpTransport.builder(props.mcpServerUrl()).requestBuilder(request).build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(props.timeout()).build();
        long started = System.nanoTime();
        try {
            client.initialize();
            List<ToolSpec> tools = client.listTools().tools().stream()
                    .map(t -> new ToolSpec(t.name(), t.description(), schema(t.inputSchema())))
                    .toList();
            return new McpSession(client, tools);
        } catch (RuntimeException e) {
            // 예외 메시지에는 헤더·대화가 섞일 수 있어 남기지 않는다 (CLAUDE.md 4장)
            log.warn("mcp open ok=false {}ms", (System.nanoTime() - started) / 1_000_000);
            client.closeGracefully();
            throw new ToolGateway.Unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(Object inputSchema) {
        return inputSchema instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("type", "object");
    }

    private record McpSession(McpSyncClient client, List<ToolSpec> tools) implements Session {

        @Override
        public Outcome call(String name, Map<String, Object> arguments) {
            try {
                CallToolResult result = client.callTool(new CallToolRequest(name, arguments));
                String text = result.content().stream()
                        .filter(TextContent.class::isInstance).map(c -> ((TextContent) c).text())
                        .collect(Collectors.joining("\n"));
                return new Outcome(text, Boolean.TRUE.equals(result.isError()));
            } catch (RuntimeException e) {
                // 없는 도구 이름, mcp-server 응답 없음 등. 모델에는 짧은 문장만
                return new Outcome("{\"message\":\"도구를 실행하지 못했어요. 잠시 후 다시 시도해 주세요.\"}", true);
            }
        }

        @Override
        public void close() {
            client.closeGracefully();
        }
    }
}
