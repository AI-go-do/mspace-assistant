package kr.ac.chosun.mspace.assistant.agent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 확인 중계: 가짜 mcp-server(WireMock)로 진짜 HTTP 경로를 탄다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfirmRelayTest {

    static final WireMockServer mcp = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        mcp.start();
    }

    @DynamicPropertySource
    static void mcpUrl(DynamicPropertyRegistry registry) {
        registry.add("mspace.agent.mcp-server-url", mcp::baseUrl);
        registry.add("mspace.agent.timeout", () -> "1s");
    }

    @LocalServerPort
    int port;

    @Autowired
    AgentProperties props;

    @AfterEach
    void reset() {
        mcp.resetAll();
    }

    HttpResponse<String> confirm(Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/agent/confirmations/c_1"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"participantStudentIds\":[]}"));
        headers.forEach(request::header);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    @DisplayName("기본 설정은 Authorization만 넘긴다. X-User-Id는 local 프로필에서만")
    void defaultForwardsOnlyAuthorization() throws Exception {
        // given
        mcp.stubFor(post("/api/confirmations/c_1").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"reservationId\":7}")));

        // when
        confirm(Map.of("Authorization", "Bearer t", "X-User-Id", "2"));

        // then
        assertThat(props.forwardHeaders()).containsExactly("Authorization");
        mcp.verify(postRequestedFor(urlEqualTo("/api/confirmations/c_1"))
                .withHeader("Authorization", equalTo("Bearer t"))
                .withoutHeader("X-User-Id"));
    }

    @Test
    @DisplayName("mcp-server 응답(상태·본문)을 그대로 넘기고, 입실 번호가 담길 수 있어 Cache-Control: no-store")
    void relaysWithNoStore() throws Exception {
        // given
        mcp.stubFor(post("/api/confirmations/c_1").willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"RESERVATION_TIME_TAKEN\",\"message\":\"이미 예약된 시간이에요.\"}")));

        // when
        HttpResponse<String> response = confirm(Map.of("Authorization", "Bearer t"));

        // then
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("RESERVATION_TIME_TAKEN");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @Test
    @DisplayName("mcp-server 연결이 끊기면 500이 아니라 503 TOOLS_UNAVAILABLE")
    void brokenConnectionIs503() throws Exception {
        // given
        mcp.stubFor(post("/api/confirmations/c_1").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // when
        HttpResponse<String> response = confirm(Map.of("Authorization", "Bearer t"));

        // then
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("TOOLS_UNAVAILABLE").contains("예약 서버에");
    }
}
