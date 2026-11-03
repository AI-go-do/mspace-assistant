package kr.ac.chosun.mspace.assistant.mcp;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 도구 통합 테스트 공통부: 가짜 코어(WireMock) + 진짜 MCP 클라이언트(공식 Java SDK, Streamable HTTP).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class McpToolTestSupport {

    /**
     * 테스트 클래스 여러 개가 Spring 문맥 하나를 같이 쓴다(문맥 캐시). 그래서 가짜 코어도 JVM에 하나만 띄워
     * 주소가 바뀌지 않게 한다. 클래스마다 새로 띄우면 캐시된 문맥이 예전 포트를 불러 실패한다.
     */
    protected static final WireMockServer core = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        core.start();
    }

    @DynamicPropertySource
    static void coreUrl(DynamicPropertyRegistry registry) {
        registry.add("mspace.core.base-url", core::baseUrl);
        registry.add("mspace.core.timeout", () -> "1s");
    }

    @LocalServerPort
    protected int port;

    private McpSyncClient client;

    @AfterEach
    void closeClient() {
        core.resetAll();
        if (client != null) {
            client.closeGracefully();
        }
    }

    protected McpSyncClient connect(Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder();
        headers.forEach(request::header);
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .requestBuilder(request)
                .build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
        client.initialize();
        return client;
    }

    protected CallToolResult call(Map<String, String> headers, String tool, Map<String, Object> args) {
        return connect(headers).callTool(new CallToolRequest(tool, args));
    }

    protected static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
