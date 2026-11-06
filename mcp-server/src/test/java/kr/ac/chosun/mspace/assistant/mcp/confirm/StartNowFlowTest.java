package kr.ac.chosun.mspace.assistant.mcp.confirm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** start_now_reservation(FR-RSV-103) 두 단계. 시계는 실제라 끝 시각을 지금 기준으로 만든다 */
class StartNowFlowTest extends McpToolTestSupport {

    static final Map<String, String> ME = Map.of("X-User-Id", "1");
    static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 지금을 30분 칸으로 올린 뒤 30분 더: 늘 "지금 이후, 2시간 안"인 끝 시각 */
    static OffsetDateTime validEnd() {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.MINUTES);
        int rem = now.getMinute() % 30;
        return now.plusMinutes(rem == 0 ? 30 : 30 - rem).plusMinutes(30);
    }

    HttpResponse<String> confirm(String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/confirmations/" + token))
                .header("Content-Type", "application/json").header("X-User-Id", "1")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    static String token(CallToolResult result) {
        Matcher m = Pattern.compile("\"confirmToken\":\"(c_[A-Za-z0-9_-]+)\"").matcher(text(result));
        assertThat(m.find()).as(text(result)).isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("도구 목록: start_now_reservation은 쓰기 도구이고 입력에 시작 시각·학번이 없다")
    void listsTool() {
        // when
        Tool tool = connect(ME).listTools().tools().stream()
                .filter(t -> t.name().equals("start_now_reservation")).findFirst().orElseThrow();

        // then
        assertThat(tool.annotations().readOnlyHint()).isFalse();
        assertThat(tool.inputSchema().toString()).doesNotContainIgnoringCase("student").doesNotContain("startAt");
    }

    @Test
    @DisplayName("확인 없이 도구만 부르면 확인안만 나오고 즉시 시작 API는 불리지 않는다")
    void proposalDoesNotCallCore() {
        // given
        OffsetDateTime end = validEnd();

        // when
        CallToolResult result = call(ME, "start_now_reservation", Map.of("roomId", 2, "endAt", end.format(ISO), "headcount", 3));

        // then
        assertThat(result.isError()).as(text(result)).isFalse();
        assertThat(text(result)).contains("2번 방을 지금부터 " + end.toLocalTime() + "까지 3명으로 바로 쓸까요?")
                .contains("10분 안에", "2명의 학번은 확인 화면에서", "\"needsParticipants\":2");
        core.verify(0, postRequestedFor(urlEqualTo("/api/reservations/instant")));
    }

    @Test
    @DisplayName("끝 시각이 30분 칸이 아니거나 2시간을 넘으면 확인안을 만들지 않는다")
    void rejectsBadEnd() {
        // given
        OffsetDateTime end = validEnd();

        // when
        CallToolResult offSlot = call(ME, "start_now_reservation", Map.of("roomId", 2, "endAt", end.plusMinutes(10).format(ISO), "headcount", 3));
        CallToolResult tooLong = call(ME, "start_now_reservation", Map.of("roomId", 2, "endAt", end.plusHours(3).format(ISO), "headcount", 3));
        CallToolResult past = call(ME, "start_now_reservation", Map.of("roomId", 2, "endAt", end.minusHours(3).format(ISO), "headcount", 3));

        // then
        assertThat(offSlot.isError()).isTrue();
        assertThat(text(offSlot)).contains("정각 또는 30분");
        assertThat(tooLong.isError()).isTrue();
        assertThat(text(tooLong)).contains("지금부터", "까지 고를 수 있어요");
        assertThat(past.isError()).isTrue();
    }

    @Test
    @DisplayName("확인하면 즉시 시작 API를 멱등 키·AGENT 표시·학번으로 부르고, 토큰 재사용은 409")
    void confirmStartsNow() throws Exception {
        // given
        OffsetDateTime end = validEnd();
        core.stubFor(post("/api/reservations/instant").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"id": 9, "roomId": 2, "startAt": "%s", "endAt": "%s", "status": "BOOKED", "headcount": 3, "accessCode": "482913"}"""
                        .formatted(end.minusMinutes(40).format(ISO), end.format(ISO)))));
        String token = token(call(ME, "start_now_reservation", Map.of("roomId", 2, "endAt", end.format(ISO), "headcount", 3)));

        // when
        HttpResponse<String> response = confirm(token, "{\"participantStudentIds\":[\"20230002\",\"20230003\"]}");
        HttpResponse<String> again = confirm(token, "{\"participantStudentIds\":[\"20230002\",\"20230003\"]}");

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"action\":\"START_NOW\"", "\"reservationId\":9", "\"accessCode\":\"482913\"",
                "10분 안에 입실");
        core.verify(1, postRequestedFor(urlEqualTo("/api/reservations/instant"))
                .withHeader("Idempotency-Key", equalTo(token))
                .withHeader("X-Created-Via", equalTo("AGENT"))
                .withRequestBody(equalToJson("""
                        {"roomId": 2, "endAt": "%s", "participantStudentIds": ["20230002", "20230003"]}""".formatted(end.format(ISO)))));
        assertThat(again.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("학번 수가 모자라면 토큰을 쓰지 않고 400, 고쳐서 다시 누를 수 있다")
    void participantsChecked() throws Exception {
        // given
        OffsetDateTime end = validEnd();
        String token = token(call(ME, "start_now_reservation", Map.of("roomId", 2, "endAt", end.format(ISO), "headcount", 3)));

        // when
        HttpResponse<String> response = confirm(token, "{\"participantStudentIds\":[\"20230002\"]}");

        // then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("2명의 학번");
        core.verify(0, postRequestedFor(urlEqualTo("/api/reservations/instant")));
    }
}
