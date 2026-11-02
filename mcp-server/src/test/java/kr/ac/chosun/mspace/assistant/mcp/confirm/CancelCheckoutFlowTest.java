package kr.ac.chosun.mspace.assistant.mcp.confirm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.chosun.mspace.assistant.mcp.McpToolTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** cancel_reservation·checkout_reservation 두 단계. 권한은 core가 최종 판단한다 */
class CancelCheckoutFlowTest extends McpToolTestSupport {

    static final Map<String, String> ME = Map.of("X-User-Id", "1");

    /** 7: 내가 대표자, 시작 전 / 8: 내가 대표자, 사용 중(2099년 끝) / 9: 남이 대표자, 나는 참여자 */
    static final String MINE = """
            [
              {"id": 7, "room": {"id": 1, "name": "1번 방"}, "startAt": "2026-11-03T14:00:00+09:00",
               "endAt": "2026-11-03T16:00:00+09:00", "status": "BOOKED", "owner": true, "accessCode": "482913",
               "participants": [{"name": "나*준", "owner": true}]},
              {"id": 8, "room": {"id": 2, "name": "2번 방"}, "startAt": "2099-11-03T10:00:00+09:00",
               "endAt": "2099-11-03T12:00:00+09:00", "status": "IN_USE", "owner": true, "accessCode": "111111",
               "participants": [{"name": "나*준", "owner": true}]},
              {"id": 9, "room": {"id": 3, "name": "3번 방"}, "startAt": "2026-11-04T10:00:00+09:00",
               "endAt": "2026-11-04T11:00:00+09:00", "status": "BOOKED", "owner": false, "accessCode": null,
               "participants": [{"name": "박*영", "owner": true}, {"name": "나*준", "owner": false}]}
            ]""";

    @BeforeEach
    void myReservations() {
        core.stubFor(get("/api/reservations/me").willReturn(okJson(MINE)));
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

    @Test
    @DisplayName("취소 제안은 내 예약으로 확인안을 만들고, 취소 API는 부르지 않는다")
    void proposeCancel() {
        CallToolResult result = call(ME, "cancel_reservation", Map.of("reservationId", 7));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("1번 방 11월 3일(화) 14:00–16:00 예약을 취소할까요?").doesNotContain("482913");
        core.verify(0, postRequestedFor(urlMatching("/api/reservations/.*")));
    }

    @Test
    @DisplayName("확인하면 취소 API를 학생 헤더로 부른다")
    void confirmCancel() throws Exception {
        core.stubFor(post("/api/reservations/7/cancel").willReturn(okJson("{\"id\":7,\"status\":\"CANCELED\"}")));
        String token = token(call(ME, "cancel_reservation", Map.of("reservationId", 7)));

        HttpResponse<String> response = confirm(token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"action\":\"CANCEL\"", "\"status\":\"CANCELED\"", "취소했어요");
        core.verify(postRequestedFor(urlEqualTo("/api/reservations/7/cancel")).withHeader("X-User-Id", equalTo("1")));
    }

    @Test
    @DisplayName("권한: 참여자가 남의 예약 취소를 시도하면 확인안을 주지 않는다")
    void participantCannotCancel() {
        CallToolResult result = call(ME, "cancel_reservation", Map.of("reservationId", 9));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("대표자만 취소할 수 있어요");
    }

    @Test
    @DisplayName("권한: 확인 사이에 상황이 바뀌어 core가 403으로 막으면 그 결과를 그대로 돌려준다")
    void coreBlocksAtExecution() throws Exception {
        core.stubFor(post("/api/reservations/7/cancel").willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"NOT_RESERVATION_OWNER\",\"message\":\"대표자만 할 수 있습니다\"}")));
        String token = token(call(ME, "cancel_reservation", Map.of("reservationId", 7)));

        HttpResponse<String> response = confirm(token);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("NOT_RESERVATION_OWNER", "대표자만 할 수 있습니다");
    }

    @Test
    @DisplayName("내 목록에 없는 예약 id → 찾을 수 없음")
    void unknownReservation() {
        CallToolResult result = call(ME, "cancel_reservation", Map.of("reservationId", 404));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("찾을 수 없는 예약이에요");
    }

    @Test
    @DisplayName("사용 중인 예약은 취소 대신 퇴실을 안내한다")
    void inUseCannotCancel() {
        CallToolResult result = call(ME, "cancel_reservation", Map.of("reservationId", 8));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("퇴실을 해 주세요");
    }

    @Test
    @DisplayName("퇴실 제안: 끝나기 전이면 조기 퇴실 note, 확인하면 퇴실 API를 부른다")
    void checkout() throws Exception {
        core.stubFor(post("/api/reservations/8/checkout").willReturn(okJson("{\"id\":8,\"status\":\"CHECKED_OUT\"}")));
        CallToolResult proposal = call(ME, "checkout_reservation", Map.of("reservationId", 8));

        assertThat(text(proposal)).contains("2번 방", "퇴실할까요?", "조기 퇴실");
        HttpResponse<String> response = confirm(token(proposal));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"action\":\"CHECKOUT\"", "\"status\":\"CHECKED_OUT\"");
    }

    @Test
    @DisplayName("입실 전(BOOKED) 예약은 퇴실 대신 취소를 안내한다")
    void bookedCannotCheckout() {
        CallToolResult result = call(ME, "checkout_reservation", Map.of("reservationId", 7));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("아직 입실하지 않은 예약");
    }
}
