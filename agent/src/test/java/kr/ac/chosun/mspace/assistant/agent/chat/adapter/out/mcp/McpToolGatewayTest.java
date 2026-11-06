package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ToolGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 MCP 서버(공식 SDK, Streamable HTTP)를 테스트 안에 띄워 McpToolGateway가 붙는 길을 확인한다.
 * 가짜 응답을 손으로 만들지 않아서 SDK가 바뀌어도 진짜 프로토콜로 검사된다.
 */
class McpToolGatewayTest {

    private static Tomcat tomcat;
    private static McpSyncServer server;
    private static String url;
    private static final AtomicReference<String> seenAuthorization = new AtomicReference<>();

    @BeforeAll
    static void startServer() throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .mcpEndpoint("/mcp")
                .contextExtractor(request -> {
                    seenAuthorization.set(request.getHeader("Authorization"));
                    return io.modelcontextprotocol.common.McpTransportContext.EMPTY;
                })
                .build();

        Tool echo = Tool.builder("echo", Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))))
                .description("받은 글을 그대로 돌려줌")
                .build();
        Tool broken = Tool.builder("broken", Map.of("type", "object")).description("항상 오류").build();
        server = McpServer.sync(transport).serverInfo("test", "1")
                .tools(
                        SyncToolSpecification.builder().tool(echo)
                                .callHandler((ex, req) -> CallToolResult.builder()
                                        .addTextContent("{\"text\":\"" + req.arguments().get("text") + "\"}").build())
                                .build(),
                        SyncToolSpecification.builder().tool(broken)
                                .callHandler((ex, req) -> CallToolResult.builder()
                                        .addTextContent("{\"code\":\"FAIL\",\"message\":\"안 됨\"}").isError(true).build())
                                .build())
                .build();

        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.setBaseDir(System.getProperty("java.io.tmpdir") + "/mcp-gateway-test-" + port);
        Context ctx = tomcat.addContext("", null);
        Tomcat.addServlet(ctx, "mcp", transport).setAsyncSupported(true);
        ctx.addServletMapping("/mcp", "mcp");
        tomcat.getConnector();
        tomcat.start();
        url = "http://localhost:" + port;
    }

    @AfterAll
    static void stopServer() throws Exception {
        server.closeGracefully();
        tomcat.stop();
        tomcat.destroy();
    }

    private static McpToolGateway gateway(String mcpUrl) {
        var props = new AgentProperties(mcpUrl, List.of("Authorization"), 8, Duration.ofSeconds(5), null, null, 0, 0);
        return new McpToolGateway(props, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("세션을 열면 도구 목록과 입력 형식을 받아 오고, 학생 헤더를 그대로 싣는다")
    void openListsToolsAndForwardsHeaders() {
        try (var session = gateway(url).open(new UserHeaders(Map.of("Authorization", "Bearer test-token")))) {
            assertThat(session.tools()).extracting(t -> t.name()).containsExactlyInAnyOrder("echo", "broken");
            assertThat(session.tools()).filteredOn(t -> t.name().equals("echo")).first()
                    .satisfies(t -> assertThat(t.inputSchema()).containsEntry("type", "object"));
            assertThat(seenAuthorization.get()).isEqualTo("Bearer test-token");
        }
    }

    @Test
    @DisplayName("도구를 부르면 글 결과를 모아 돌려준다")
    void callReturnsText() {
        try (var session = gateway(url).open(new UserHeaders(Map.of()))) {
            ToolGateway.Outcome out = session.call("echo", Map.of("text", "안녕"));
            assertThat(out.isError()).isFalse();
            assertThat(out.json()).contains("안녕");
        }
    }

    @Test
    @DisplayName("도구가 오류를 내면 오류로 표시한다")
    void callMarksToolError() {
        try (var session = gateway(url).open(new UserHeaders(Map.of()))) {
            assertThat(session.call("broken", Map.of()).isError()).isTrue();
        }
    }

    @Test
    @DisplayName("없는 도구를 부르면 예외 대신 짧은 오류 문장을 돌려준다")
    void unknownToolIsShortError() {
        try (var session = gateway(url).open(new UserHeaders(Map.of()))) {
            ToolGateway.Outcome out = session.call("no_such_tool", Map.of());
            assertThat(out.isError()).isTrue();
            assertThat(out.json()).contains("도구를 실행하지 못했어요");
        }
    }

    @Test
    @DisplayName("mcp-server에 붙지 못하면 Unavailable로 알린다")
    void unreachableServerIsUnavailable() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        var gateway = gateway("http://localhost:" + closedPort);
        var headers = new UserHeaders(Map.of());
        assertThatThrownBy(() -> gateway.open(headers)).isInstanceOf(ToolGateway.Unavailable.class);
    }
}
