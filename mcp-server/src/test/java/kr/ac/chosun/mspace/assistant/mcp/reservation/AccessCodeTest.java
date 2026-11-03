package kr.ac.chosun.mspace.assistant.mcp.reservation;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.mcp.McpToolTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 입실 번호: 도구(모델)는 번호를 모르고, 화면 카드(REST)만 대표자에게 번호를 준다 */
class AccessCodeTest extends McpToolTestSupport {

    static final Map<String, String> ME = Map.of("X-User-Id", "1");

    /** 7: 대표자·예약됨 / 8: 대표자·퇴실함 / 9: 참여자·예약됨 (core는 참여자에게 번호를 null로 준다) */
    static final String MINE = """
            [
              {"id": 7, "room": {"id": 1, "name": "1번 방"}, "startAt": "2099-11-03T14:00:00+09:00",
               "endAt": "2099-11-03T16:00:00+09:00", "status": "BOOKED", "owner": true, "accessCode": "482913",
               "participants": [{"name": "나*준", "owner": true}]},
              {"id": 8, "room": {"id": 2, "name": "2번 방"}, "startAt": "2099-11-01T10:00:00+09:00",
               "endAt": "2099-11-01T12:00:00+09:00", "status": "CHECKED_OUT", "owner": true, "accessCode": "111111",
               "participants": [{"name": "나*준", "owner": true}]},
              {"id": 9, "room": {"id": 3, "name": "3번 방"}, "startAt": "2099-11-04T10:00:00+09:00",
               "endAt": "2099-11-04T11:00:00+09:00", "status": "BOOKED", "owner": false, "accessCode": null,
               "participants": [{"name": "박*영", "owner": true}, {"name": "나*준", "owner": false}]}
            ]""";

    @BeforeEach
    void myReservations() {
        core.stubFor(get("/api/reservations/me").willReturn(okJson(MINE)));
    }

    HttpResponse<String> card(long reservationId, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/reservations/" + reservationId + "/access-code")).GET();
        headers.forEach(request::header);
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    @DisplayName("도구 목록에 show_access_code가 읽기 전용으로 보인다")
    void listsTool() {
        // when
        Tool tool = connect(ME).listTools().tools().stream()
                .filter(t -> t.name().equals("show_access_code")).findFirst().orElseThrow();

        // then
        assertThat(tool.annotations().readOnlyHint()).isTrue();
        assertThat(tool.description()).contains("번호를 돌려주지 않는다", "마음대로 하나를 고르지 않는다");
    }

    @Test
    @DisplayName("대표자: 도구 결과는 카드 표시만 알리고 번호는 없다")
    void ownerGetsCardWithoutCode() {
        // given
        Map<String, Object> args = Map.of("reservationId", 7);

        // when
        CallToolResult result = call(ME, "show_access_code", args);

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"cardShown\":true", "\"owner\":true", "\"reservationId\":7")
                .doesNotContain("482913", "accessCode");
    }

    @Test
    @DisplayName("id 없이 물었는데 끝나지 않은 예약이 여러 개면 고를 목록을 주고 카드는 띄우지 않는다")
    void asksWhichWhenSeveralActive() {
        // given: MINE에는 끝나지 않은 예약이 7(대표자), 9(참여자) 두 개
        Map<String, Object> noId = new HashMap<>();

        // when
        CallToolResult result = call(ME, "show_access_code", noId);

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"cardShown\":false", "\"choices\":[", "\"reservationId\":7",
                        "\"reservationId\":9", "\"roomName\":\"3번 방\"", "어느 예약의 입실 번호를 볼까요?")
                .doesNotContain("482913", "\"reservationId\":8");
    }

    @Test
    @DisplayName("id 없이 물었는데 끝나지 않은 예약이 1개면 그 예약으로 답한다")
    void usesOnlyActiveReservation() {
        // given: 끝나지 않은 예약은 대표자 예약 7 하나
        core.stubFor(get("/api/reservations/me").willReturn(okJson("""
                [{"id": 7, "room": {"id": 1, "name": "1번 방"}, "startAt": "2099-11-03T14:00:00+09:00",
                  "endAt": "2099-11-03T16:00:00+09:00", "status": "BOOKED", "owner": true, "accessCode": "482913",
                  "participants": [{"name": "나*준", "owner": true}]},
                 {"id": 8, "room": {"id": 2, "name": "2번 방"}, "startAt": "2099-11-01T10:00:00+09:00",
                  "endAt": "2099-11-01T12:00:00+09:00", "status": "CHECKED_OUT", "owner": true, "accessCode": "111111",
                  "participants": [{"name": "나*준", "owner": true}]}]""")));

        // when
        CallToolResult result = call(ME, "show_access_code", new HashMap<>());

        // then
        assertThat(text(result)).contains("\"reservationId\":7", "\"cardShown\":true", "\"choices\":[]")
                .doesNotContain("482913");
    }

    @Test
    @DisplayName("id 없이 물었는데 끝나지 않은 예약이 1개이고 참여자면 대표자에게 문의하라고 한다")
    void onlyActiveIsParticipant() {
        // given
        core.stubFor(get("/api/reservations/me").willReturn(okJson("""
                [{"id": 9, "room": {"id": 3, "name": "3번 방"}, "startAt": "2099-11-04T10:00:00+09:00",
                  "endAt": "2099-11-04T11:00:00+09:00", "status": "BOOKED", "owner": false, "accessCode": null,
                  "participants": [{"name": "박*영", "owner": true}, {"name": "나*준", "owner": false}]}]""")));

        // when
        CallToolResult result = call(ME, "show_access_code", new HashMap<>());

        // then
        assertThat(text(result)).contains("\"reservationId\":9", "\"cardShown\":false", "대표자에게 문의하세요");
    }

    @Test
    @DisplayName("id 없이 물었는데 잡혀 있는 예약이 없으면(끝난 예약만 있음) 예약이 없다고 안내한다")
    void noActiveReservation() {
        // given
        core.stubFor(get("/api/reservations/me").willReturn(okJson("""
                [{"id": 8, "room": {"id": 2, "name": "2번 방"}, "startAt": "2099-11-01T10:00:00+09:00",
                  "endAt": "2099-11-01T12:00:00+09:00", "status": "CHECKED_OUT", "owner": true, "accessCode": "111111",
                  "participants": [{"name": "나*준", "owner": true}]}]""")));

        // when
        CallToolResult result = call(ME, "show_access_code", new HashMap<>());

        // then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("지금 잡혀 있는 예약이 없어요").doesNotContain("111111");
    }

    @Test
    @DisplayName("참여자: 대표자에게 문의하라는 안내, 카드 없음")
    void participantIsToldToAskOwner() {
        // given
        Map<String, Object> args = Map.of("reservationId", 9);

        // when
        CallToolResult result = call(ME, "show_access_code", args);

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"cardShown\":false", "대표자에게 문의하세요");
    }

    @Test
    @DisplayName("끝난 예약: 번호를 쓸 수 없다는 안내, 카드 없음")
    void finishedHasNoCard() {
        // given
        Map<String, Object> args = Map.of("reservationId", 8);

        // when
        CallToolResult result = call(ME, "show_access_code", args);

        // then
        assertThat(text(result)).contains("\"cardShown\":false", "입실 번호를 쓸 수 없어요").doesNotContain("111111");
    }

    @Test
    @DisplayName("없는 예약: 도구 오류")
    void unknownReservation() {
        // given
        Map<String, Object> args = Map.of("reservationId", 99);

        // when
        CallToolResult result = call(ME, "show_access_code", args);

        // then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("찾을 수 없는 예약");
    }

    @Test
    @DisplayName("카드 API: 대표자에게는 번호를 주고, 학생 헤더로 core를 부르고, 캐시하지 않게 한다")
    void cardRevealsCodeToOwner() throws Exception {
        // when
        HttpResponse<String> response = card(7, ME);

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"accessCode\":\"482913\"", "\"roomName\":\"1번 방\"");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        core.verify(getRequestedFor(urlEqualTo("/api/reservations/me")).withHeader("X-User-Id", equalTo("1")));
    }

    @Test
    @DisplayName("카드 API: 참여자는 403과 대표자 문의 안내")
    void cardRefusesParticipant() throws Exception {
        // when
        HttpResponse<String> response = card(9, ME);

        // then
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("NOT_RESERVATION_OWNER", "대표자에게 문의하세요");
    }

    @Test
    @DisplayName("카드 API: 끝난 예약은 409, 번호를 주지 않는다")
    void cardRefusesFinished() throws Exception {
        // when
        HttpResponse<String> response = card(8, ME);

        // then
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).doesNotContain("111111");
    }

    @Test
    @DisplayName("카드 API: 로그인 헤더가 없으면 401, core를 부르지 않는다")
    void cardNeedsLogin() throws Exception {
        // when
        HttpResponse<String> response = card(7, Map.of());

        // then
        assertThat(response.statusCode()).isEqualTo(401);
        core.verify(0, getRequestedFor(urlEqualTo("/api/reservations/me")));
    }
}
