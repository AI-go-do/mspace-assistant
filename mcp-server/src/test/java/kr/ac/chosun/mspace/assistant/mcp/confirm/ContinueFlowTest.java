package kr.ac.chosun.mspace.assistant.mcp.confirm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.chosun.mspace.assistant.mcp.McpToolTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** continue_reservation(FR-RSV-105) 두 단계. 시계는 실제 시각이라 예약 시각을 지금 기준으로 만든다 */
class ContinueFlowTest extends McpToolTestSupport {

    static final Map<String, String> ME = Map.of("X-User-Id", "1");
    static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /** 21: 사용 중, 5분 뒤 끝(연장 가능) / 22: 사용 중, 1시간 뒤 끝(아직 이름) / 23: 입실 전 / 24: 남이 대표자 */
    OffsetDateTime soonEnd;

    @BeforeEach
    void myReservations() {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.MINUTES);
        soonEnd = now.plusMinutes(5);
        OffsetDateTime laterEnd = now.plusMinutes(60);
        core.stubFor(get("/api/reservations/me").willReturn(okJson("""
                [
                  {"id": 21, "room": {"id": 1, "name": "1번 방"}, "startAt": "%s", "endAt": "%s", "status": "IN_USE",
                   "owner": true, "accessCode": "111111", "participants": [{"name": "나*준", "owner": true}]},
                  {"id": 22, "room": {"id": 2, "name": "2번 방"}, "startAt": "%s", "endAt": "%s", "status": "IN_USE",
                   "owner": true, "accessCode": "222222", "participants": [{"name": "나*준", "owner": true}]},
                  {"id": 23, "room": {"id": 3, "name": "3번 방"}, "startAt": "2099-11-03T14:00:00+09:00",
                   "endAt": "2099-11-03T16:00:00+09:00", "status": "BOOKED", "owner": true, "accessCode": "333333",
                   "participants": [{"name": "나*준", "owner": true}]},
                  {"id": 24, "room": {"id": 4, "name": "4번 방"}, "startAt": "%s", "endAt": "%s", "status": "IN_USE",
                   "owner": false, "accessCode": null,
                   "participants": [{"name": "박*영", "owner": true}, {"name": "나*준", "owner": false}]}
                ]""".formatted(
                soonEnd.minusMinutes(60).format(ISO), soonEnd.format(ISO),
                laterEnd.minusMinutes(60).format(ISO), laterEnd.format(ISO),
                soonEnd.minusMinutes(60).format(ISO), soonEnd.format(ISO)))));
    }

    static String token(CallToolResult result) {
        Matcher m = Pattern.compile("\"confirmToken\":\"(c_[A-Za-z0-9_-]+)\"").matcher(text(result));
        assertThat(m.find()).as(text(result)).isTrue();
        return m.group(1);
    }

    HttpResponse<String> confirm(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/confirmations/" + token))
                .header("X-User-Id", "1").POST(HttpRequest.BodyPublishers.noBody()).build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    String created(OffsetDateTime newEnd) {
        return """
                {"id": 31, "roomId": 1, "startAt": "%s", "endAt": "%s", "status": "IN_USE", "headcount": 3,
                 "accessCode": "654321"}""".formatted(soonEnd.minusMinutes(2).format(ISO), newEnd.format(ISO));
    }

    @Test
    @DisplayName("연장 제안은 확인안만 주고 연장 API는 부르지 않는다")
    void propose() {
        // when
        CallToolResult result = call(ME, "continue_reservation", Map.of("reservationId", 21, "minutes", 60));

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("1번 방", soonEnd.plusMinutes(60).format(HM) + "까지 60분 연장할까요?",
                "한 번만", "입실 번호가 새로 나와요").doesNotContain("111111");
        core.verify(0, postRequestedFor(urlMatching("/api/reservations/.*")));
    }

    @Test
    @DisplayName("확인하면 연장 API를 새 끝 시각·멱등 키·X-Created-Via: AGENT로 부르고 새 입실 번호를 화면에 준다")
    void confirmContinues() throws Exception {
        // given
        OffsetDateTime newEnd = soonEnd.plusMinutes(60);
        core.stubFor(post("/api/reservations/21/continue").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody(created(newEnd))));
        String token = token(call(ME, "continue_reservation", Map.of("reservationId", 21, "minutes", 60)));

        // when
        HttpResponse<String> response = confirm(token);

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"action\":\"CONTINUE\"", "\"status\":\"IN_USE\"", "654321",
                newEnd.format(HM) + "까지 연장했어요");
        core.verify(postRequestedFor(urlEqualTo("/api/reservations/21/continue"))
                .withHeader("X-User-Id", equalTo("1"))
                .withHeader("Idempotency-Key", equalTo(token))
                .withHeader("X-Created-Via", equalTo("AGENT"))
                .withRequestBody(containing("\"endAt\""))
                .withRequestBody(containing(newEnd.toLocalDate() + "T" + newEnd.format(HM))));
    }

    @Test
    @DisplayName("확인 토큰 재사용: 두 번째 확인은 409이고 연장 API는 한 번만 불린다")
    void tokenReuse() throws Exception {
        // given
        core.stubFor(post("/api/reservations/21/continue").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody(created(soonEnd.plusMinutes(30)))));
        String token = token(call(ME, "continue_reservation", Map.of("reservationId", 21, "minutes", 30)));

        // when
        HttpResponse<String> first = confirm(token);
        HttpResponse<String> second = confirm(token);

        // then
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("CONFIRMATION_USED");
        core.verify(1, postRequestedFor(urlEqualTo("/api/reservations/21/continue")));
    }

    @Test
    @DisplayName("끝나기 10분 전보다 이르면 확인안을 주지 않는다")
    void tooEarly() {
        CallToolResult result = call(ME, "continue_reservation", Map.of("reservationId", 22, "minutes", 30));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("끝나기 10분 전부터").doesNotContain("confirmToken");
    }

    @Test
    @DisplayName("30분 단위가 아니거나 120분을 넘으면 확인안을 주지 않는다")
    void invalidMinutes() {
        CallToolResult odd = call(ME, "continue_reservation", Map.of("reservationId", 21, "minutes", 45));
        CallToolResult tooLong = call(ME, "continue_reservation", Map.of("reservationId", 21, "minutes", 150));

        assertThat(odd.isError()).isTrue();
        assertThat(text(odd)).contains("30분 단위로 최대 120분");
        assertThat(tooLong.isError()).isTrue();
        assertThat(text(tooLong)).contains("30분 단위로 최대 120분");
    }

    @Test
    @DisplayName("입실 전(BOOKED) 예약은 연장 대신 입실 먼저를 안내한다")
    void bookedCannotContinue() {
        CallToolResult result = call(ME, "continue_reservation", Map.of("reservationId", 23, "minutes", 30));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("입실한 뒤에 연장할 수 있어요");
    }

    @Test
    @DisplayName("권한: 참여자는 연장 확인안을 받지 못한다")
    void participantCannotContinue() {
        CallToolResult result = call(ME, "continue_reservation", Map.of("reservationId", 24, "minutes", 30));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("대표자만 연장할 수 있어요");
    }

    @Test
    @DisplayName("이미 한 번 연장했으면 core 409를 그대로 안내한다")
    void limitFromCore() throws Exception {
        // given
        core.stubFor(post("/api/reservations/21/continue").willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"RESERVATION_EXTENSION_LIMIT\",\"message\":\"연장은 한 번만 할 수 있습니다\"}")));
        String token = token(call(ME, "continue_reservation", Map.of("reservationId", 21, "minutes", 30)));

        // when
        HttpResponse<String> response = confirm(token);

        // then
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("RESERVATION_EXTENSION_LIMIT");
    }

    @Test
    @DisplayName("연장 중 core 500이면 연장됐는지 모른다고 안내하고 다시 보내지 않는다")
    void serverErrorIsUnknown() throws Exception {
        // given
        core.stubFor(post("/api/reservations/21/continue").willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"INTERNAL_ERROR\",\"message\":\"서버 오류\"}")));
        String token = token(call(ME, "continue_reservation", Map.of("reservationId", 21, "minutes", 30)));

        // when
        HttpResponse<String> response = confirm(token);

        // then
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("CONTINUE_UNKNOWN");
        core.verify(1, postRequestedFor(urlEqualTo("/api/reservations/21/continue")));
    }
}
