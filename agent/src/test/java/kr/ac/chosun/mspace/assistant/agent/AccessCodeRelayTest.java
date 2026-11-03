package kr.ac.chosun.mspace.assistant.agent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 입실 번호 카드 API: 학생 헤더로 mcp-server를 그대로 부르고, 모델은 거치지 않는다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessCodeRelayTest {

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

    HttpResponse<String> card(long id, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/agent/reservations/" + id + "/access-code")).GET();
        headers.forEach(request::header);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    @DisplayName("대표자: mcp-server 답(번호)을 그대로, 학생 헤더를 실어, 캐시하지 않게")
    void relaysOwnerCode() throws Exception {
        mcpServer.stubFor(get("/api/reservations/7/access-code").willReturn(okJson(
                "{\"reservationId\":7,\"roomName\":\"1번 방\",\"accessCode\":\"482913\"}")));

        HttpResponse<String> response = card(7, Map.of("X-User-Id", "1"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"accessCode\":\"482913\"");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        mcpServer.verify(getRequestedFor(urlEqualTo("/api/reservations/7/access-code"))
                .withHeader("X-User-Id", equalTo("1")));
    }

    @Test
    @DisplayName("참여자: mcp-server의 403과 대표자 문의 안내를 그대로")
    void relaysNotOwner() throws Exception {
        mcpServer.stubFor(get("/api/reservations/9/access-code").willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"NOT_RESERVATION_OWNER\",\"message\":\"대표자가 아니라 입실 번호를 알 수 없어요. 대표자에게 문의하세요.\"}")));

        HttpResponse<String> response = card(9, Map.of("X-User-Id", "1"));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("대표자에게 문의하세요");
    }

    @Test
    @DisplayName("로그인 헤더가 없으면 401, mcp-server를 부르지 않는다")
    void needsLogin() throws Exception {
        HttpResponse<String> response = card(7, Map.of());

        assertThat(response.statusCode()).isEqualTo(401);
        mcpServer.verify(0, getRequestedFor(urlMatching("/api/reservations/.*")));
    }

    @Test
    @DisplayName("mcp-server가 꺼져 있으면 503 TOOLS_UNAVAILABLE")
    void mcpServerDown() throws Exception {
        mcpServer.stubFor(get(urlMatching("/api/reservations/.*"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> response = card(7, Map.of("X-User-Id", "1"));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("TOOLS_UNAVAILABLE");
    }
}
