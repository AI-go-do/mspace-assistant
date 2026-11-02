package kr.ac.chosun.mspace.assistant.mcp.confirm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.chosun.mspace.assistant.mcp.McpToolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** create_reservation 두 단계: 도구는 확인안만, 실행은 확인 API로만 (CLAUDE.md 7장 완료 기준) */
class CreateReservationFlowTest extends McpToolTestSupport {

    static final Map<String, String> ME = Map.of("X-User-Id", "1");
    static final Map<String, Object> ARGS = Map.of("roomId", 1, "startAt", "2026-11-03T14:00:00+09:00",
            "endAt", "2026-11-03T16:00:00+09:00", "headcount", 3);
    static final String CREATED = """
            {"id": 7, "roomId": 1, "startAt": "2026-11-03T14:00:00+09:00", "endAt": "2026-11-03T16:00:00+09:00",
             "status": "BOOKED", "headcount": 3, "accessCode": "482913"}""";

    String propose() {
        CallToolResult result = call(ME, "create_reservation", ARGS);
        assertThat(result.isError()).isFalse();
        Matcher m = Pattern.compile("\"confirmToken\":\"(c_[A-Za-z0-9_-]+)\"").matcher(text(result));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    HttpResponse<String> confirm(String token, String userId, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/confirmations/" + token))
                .header("Content-Type", "application/json").header("X-User-Id", userId)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    static final String TWO_IDS = "{\"participantStudentIds\":[\"20230002\",\"20230003\"]}";

    @Test
    @DisplayName("도구 목록: create_reservation은 쓰기 도구이고, 입력에 학번이 없다")
    void listsTool() {
        Tool tool = connect(ME).listTools().tools().stream()
                .filter(t -> t.name().equals("create_reservation")).findFirst().orElseThrow();

        assertThat(tool.annotations().readOnlyHint()).isFalse();
        assertThat(tool.inputSchema().toString()).doesNotContainIgnoringCase("student");
    }

    @Test
    @DisplayName("확인 없이 쓰기 도구 실행 시도: 도구를 불러도 예약 API는 불리지 않고 확인안만 나온다")
    void proposalDoesNotCallCore() {
        CallToolResult result = call(ME, "create_reservation", ARGS);

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("1번 방, 11월 3일(화) 14:00–16:00, 3명으로 예약할까요?")
                .contains("2명의 학번은 확인 화면에서", "\"needsParticipants\":2");
        core.verify(0, postRequestedFor(urlEqualTo("/api/reservations")));
    }

    @Test
    @DisplayName("확인하면 예약 API를 멱등 키(=확인 토큰)와 학번으로 부르고, 입실 번호는 화면용 응답에만")
    void confirmCreates() throws Exception {
        // given
        core.stubFor(post("/api/reservations").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody(CREATED)));
        String token = propose();

        // when
        HttpResponse<String> response = confirm(token, "1", TWO_IDS);

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"reservationId\":7", "\"accessCode\":\"482913\"", "\"replayed\":false");
        core.verify(postRequestedFor(urlEqualTo("/api/reservations"))
                .withHeader("Idempotency-Key", equalTo(token))
                .withHeader("X-User-Id", equalTo("1"))
                .withRequestBody(equalToJson("""
                        {"roomId": 1, "startAt": "2026-11-03T14:00:00+09:00", "endAt": "2026-11-03T16:00:00+09:00",
                         "participantStudentIds": ["20230002", "20230003"]}""")));
    }

    @Test
    @DisplayName("확인 토큰 재사용: 두 번째 확인은 409이고 예약 API는 한 번만 불린다")
    void tokenReuse() throws Exception {
        core.stubFor(post("/api/reservations").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody(CREATED)));
        String token = propose();

        confirm(token, "1", TWO_IDS);
        HttpResponse<String> second = confirm(token, "1", TWO_IDS);

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("CONFIRMATION_USED", "이미 처리한 확인이에요");
        core.verify(1, postRequestedFor(urlEqualTo("/api/reservations")));
    }

    @Test
    @DisplayName("다른 학생이 남의 확인 토큰으로 실행하면 404이고 예약 API는 불리지 않는다")
    void otherUsersToken() throws Exception {
        String token = propose();

        HttpResponse<String> response = confirm(token, "2", TWO_IDS);

        assertThat(response.statusCode()).isEqualTo(404);
        core.verify(0, postRequestedFor(urlEqualTo("/api/reservations")));
    }

    @Test
    @DisplayName("학번 수가 인원과 안 맞으면 400, 토큰은 그대로라 학번만 고쳐 다시 확인할 수 있다")
    void wrongParticipantsKeepsToken() throws Exception {
        core.stubFor(post("/api/reservations").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody(CREATED)));
        String token = propose();

        HttpResponse<String> wrong = confirm(token, "1", "{\"participantStudentIds\":[\"20230002\"]}");
        HttpResponse<String> fixed = confirm(token, "1", TWO_IDS);

        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).contains("2명의 학번을 모두 입력");
        assertThat(fixed.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("core가 첫 요청에 응답하지 않으면 같은 멱등 키로 한 번 더 보낸다 (이미 만들어졌으면 200)")
    void retriesWithSameKeyOnTimeout() throws Exception {
        // given: 첫 요청은 2초 지연(타임아웃 1초), 두 번째는 같은 키 재전송이라 200
        core.stubFor(post("/api/reservations").inScenario("timeout").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(201).withFixedDelay(2_000)
                        .withHeader("Content-Type", "application/json").withBody(CREATED))
                .willSetStateTo("retried"));
        core.stubFor(post("/api/reservations").inScenario("timeout").whenScenarioStateIs("retried")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(CREATED)));
        String token = propose();

        // when
        HttpResponse<String> response = confirm(token, "1", TWO_IDS);

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"replayed\":true");
        core.verify(2, postRequestedFor(urlEqualTo("/api/reservations")).withHeader("Idempotency-Key", equalTo(token)));
    }

    @Test
    @DisplayName("core 409(조회와 확인 사이에 누가 먼저 잡음) → 같은 코드와 문장으로 돌려준다")
    void timeTaken() throws Exception {
        core.stubFor(post("/api/reservations").willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"RESERVATION_TIME_TAKEN\",\"message\":\"이미 예약된 시간입니다\"}")));
        String token = propose();

        HttpResponse<String> response = confirm(token, "1", TWO_IDS);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("RESERVATION_TIME_TAKEN", "이미 예약된 시간입니다");
    }
}
