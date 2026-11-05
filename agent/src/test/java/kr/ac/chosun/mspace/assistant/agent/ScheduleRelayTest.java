package kr.ac.chosun.mspace.assistant.agent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 내 시간표 화면 API: 학생 헤더로 mcp-server /api/schedule/me를 그대로 부르고, 모델은 거치지 않는다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class ScheduleRelayTest {

    static final WireMockServer mcpServer = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        mcpServer.start();
    }

    @DynamicPropertySource
    static void mcpServerUrl(DynamicPropertyRegistry registry) {
        registry.add("mspace.agent.mcp-server-url", mcpServer::baseUrl);
    }

    @LocalServerPort
    int port;

    @AfterEach
    void reset() {
        mcpServer.resetAll();
    }

    static final String BODY = """
            {"classes":[{"dayOfWeek":"FRI","startTime":"13:00","endTime":"14:50","name":"자료구조"}]}""";

    HttpResponse<String> send(String method, Map<String, String> headers, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/agent/schedule"))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        headers.forEach(request::header);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    @DisplayName("PUT: 화면 본문과 학생 헤더를 그대로 넘기고 답을 캐시하지 않게 돌려준다")
    void relaysPut() throws Exception {
        mcpServer.stubFor(put("/api/schedule/me").willReturn(okJson(BODY)));

        HttpResponse<String> response = send("PUT", Map.of("X-User-Id", "1"), BODY);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("자료구조");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        mcpServer.verify(putRequestedFor(urlEqualTo("/api/schedule/me"))
                .withHeader("X-User-Id", equalTo("1")).withRequestBody(equalToJson(BODY)));
    }

    @Test
    @DisplayName("GET: 시간표가 없으면 mcp-server의 404 NO_SCHEDULE을 그대로")
    void relaysNotFound() throws Exception {
        mcpServer.stubFor(get("/api/schedule/me").willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"NO_SCHEDULE\",\"message\":\"아직 넣은 시간표가 없어요.\"}")));

        HttpResponse<String> response = send("GET", Map.of("X-User-Id", "1"), null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("NO_SCHEDULE");
    }

    @Test
    @DisplayName("DELETE: 204를 그대로")
    void relaysDelete() throws Exception {
        mcpServer.stubFor(delete("/api/schedule/me").willReturn(aResponse().withStatus(204)));

        HttpResponse<String> response = send("DELETE", Map.of("X-User-Id", "1"), null);

        assertThat(response.statusCode()).isEqualTo(204);
        mcpServer.verify(deleteRequestedFor(urlEqualTo("/api/schedule/me")).withHeader("X-User-Id", equalTo("1")));
    }

    @Test
    @DisplayName("로그인 헤더가 없으면 401, mcp-server를 부르지 않는다")
    void needsLogin() throws Exception {
        HttpResponse<String> response = send("GET", Map.of(), null);

        assertThat(response.statusCode()).isEqualTo(401);
        mcpServer.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("mcp-server가 꺼져 있으면 503 TOOLS_UNAVAILABLE")
    void mcpServerDown() throws Exception {
        mcpServer.stubFor(get("/api/schedule/me")
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> response = send("GET", Map.of("X-User-Id", "1"), null);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("TOOLS_UNAVAILABLE");
    }
}
