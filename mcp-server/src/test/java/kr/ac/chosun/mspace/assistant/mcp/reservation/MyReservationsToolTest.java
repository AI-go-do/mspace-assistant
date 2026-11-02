package kr.ac.chosun.mspace.assistant.mcp.reservation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.mcp.McpToolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M1 목표: MCP 서버가 뜨고, 읽기 도구 하나가 가짜 코어(WireMock)를 불러 응답한다.
 * 진짜 MCP 클라이언트(공식 Java SDK)로 Streamable HTTP를 거쳐 부른다.
 */
class MyReservationsToolTest extends McpToolTestSupport {

    private static final String TWO_RESERVATIONS = """
            [
              {"id": 7, "room": {"id": 1, "name": "1번 방"},
               "startAt": "2026-11-03T14:00:00+09:00", "endAt": "2026-11-03T16:00:00+09:00",
               "status": "BOOKED", "owner": true, "accessCode": "482913",
               "participants": [{"name": "나*준", "owner": true}, {"name": "김*연", "owner": false},
                                {"name": "이*웅", "owner": false}]},
              {"id": 9, "room": {"id": 2, "name": "2번 방"},
               "startAt": "2026-11-04T10:00:00+09:00", "endAt": "2026-11-04T11:00:00+09:00",
               "status": "BOOKED", "owner": false, "accessCode": null,
               "participants": [{"name": "박*영", "owner": true}, {"name": "나*준", "owner": false},
                                {"name": "최*수", "owner": false}]}
            ]""";

    @Test
    @DisplayName("도구 목록에 my_reservations가 읽기 전용으로 보인다")
    void listsTool() {
        // when
        var tools = connect(Map.of("X-User-Id", "1")).listTools().tools();

        // then
        Tool tool = tools.stream().filter(t -> t.name().equals("my_reservations")).findFirst().orElseThrow();
        assertThat(tool.description()).contains("본인");
        assertThat(tool.annotations().readOnlyHint()).isTrue();
    }

    @Test
    @DisplayName("내 예약 2건(대표자 1, 참여자 1) → 2건, 사용자 헤더를 코어에 그대로 넘긴다")
    void returnsMyReservations() {
        // given
        core.stubFor(get("/api/reservations/me").withHeader("X-User-Id", equalTo("1"))
                .willReturn(okJson(TWO_RESERVATIONS)));

        // when
        CallToolResult result = connect(Map.of("X-User-Id", "1"))
                .callTool(new CallToolRequest("my_reservations", Map.of()));

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .contains("\"id\":7", "\"roomName\":\"1번 방\"", "\"owner\":true", "\"participantCount\":3")
                .contains("\"id\":9", "\"owner\":false")
                .contains("2026-11-03T14:00:00+09:00");
        core.verify(getRequestedFor(urlEqualTo("/api/reservations/me")).withHeader("X-User-Id", equalTo("1")));
    }

    @Test
    @DisplayName("입실 번호와 참여자 이름은 도구 결과(=모델 입력)에 나가지 않는다")
    void hidesAccessCodeAndNames() {
        // given
        core.stubFor(get("/api/reservations/me").willReturn(okJson(TWO_RESERVATIONS)));

        // when
        String body = text(connect(Map.of("X-User-Id", "1"))
                .callTool(new CallToolRequest("my_reservations", Map.of())));

        // then
        assertThat(body).doesNotContain("482913", "accessCode", "나*준", "김*연", "participants");
    }

    @Test
    @DisplayName("예약 없음 → 빈 목록")
    void empty() {
        core.stubFor(get("/api/reservations/me").willReturn(okJson("[]")));

        CallToolResult result = connect(Map.of("X-User-Id", "1"))
                .callTool(new CallToolRequest("my_reservations", Map.of()));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"reservations\":[]");
    }

    @Test
    @DisplayName("사용자 헤더 없이 부르면 코어를 부르지 않고 오류 결과 (로그인 필요)")
    void noCredential() {
        CallToolResult result = connect(Map.of())
                .callTool(new CallToolRequest("my_reservations", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("로그인이 필요합니다");
        core.verify(0, getRequestedFor(urlEqualTo("/api/reservations/me")));
    }

    @Test
    @DisplayName("코어 401 {code, message} → 코어 문장 그대로 오류 결과")
    void coreUnauthorized() {
        core.stubFor(get("/api/reservations/me").willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요합니다\"}")));

        CallToolResult result = connect(Map.of("X-User-Id", "999"))
                .callTool(new CallToolRequest("my_reservations", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("로그인이 필요합니다");
    }

    @Test
    @DisplayName("코어가 느리면(타임아웃 1초) 오류 결과로 끝나고 MCP 서버는 멈추지 않는다")
    void coreTimeout() {
        core.stubFor(get("/api/reservations/me").willReturn(okJson("[]").withFixedDelay(2_000)));

        CallToolResult result = connect(Map.of("X-User-Id", "1"))
                .callTool(new CallToolRequest("my_reservations", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("예약 서버에 연결하지 못했어요");
    }
}
