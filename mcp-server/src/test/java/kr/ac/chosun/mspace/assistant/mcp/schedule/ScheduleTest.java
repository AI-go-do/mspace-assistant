package kr.ac.chosun.mspace.assistant.mcp.schedule;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import kr.ac.chosun.mspace.assistant.mcp.McpToolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 내 시간표: 화면(REST)이 넣고, 모델(my_schedule 도구)은 읽기만 한다. core는 부르지 않는다.
 * 2026-11-06은 금요일, 2026-11-07은 토요일.
 */
class ScheduleTest extends McpToolTestSupport {

    static final String FRIDAY = """
            {"classes": [
              {"dayOfWeek": "FRI", "startTime": "13:00", "endTime": "14:50", "name": "자료구조"},
              {"dayOfWeek": "FRI", "startTime": "09:00", "endTime": "10:15", "name": "운영체제"},
              {"dayOfWeek": "MON", "startTime": "10:30", "endTime": "12:00", "name": "AI응용프로젝트"}]}""";

    /** 테스트마다 다른 학생 (메모리 저장소를 문맥끼리 같이 쓴다) */
    static Map<String, String> student() {
        return Map.of("X-User-Id", UUID.randomUUID().toString());
    }

    HttpResponse<String> send(String method, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/schedule/me"))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json");
        headers.forEach(request::header);
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    @DisplayName("도구 목록에 my_schedule이 읽기 전용, 날짜는 선택으로 보인다")
    void listsTool() {
        // when
        Tool tool = connect(student()).listTools().tools().stream()
                .filter(t -> t.name().equals("my_schedule")).findFirst().orElseThrow();

        // then
        assertThat(tool.annotations().readOnlyHint()).isTrue();
        assertThat(tool.description()).contains("searchFromAt", "NO_SCHEDULE", "지어내지 않는다");
        assertThat(tool.inputSchema().get("required")).satisfiesAnyOf(
                r -> assertThat(r).isNull(), r -> assertThat((java.util.List<Object>) r).isEmpty());
    }

    @Test
    @DisplayName("넣은 시간표로 그날 수업·마지막 끝·끝+30분을 30분 칸으로 올린 시각을 돌려준다 (14:50 → 15:30)")
    void daySchedule() throws Exception {
        // given
        Map<String, String> me = student();
        assertThat(send("PUT", me, FRIDAY).statusCode()).isEqualTo(200);

        // when
        CallToolResult result = call(me, "my_schedule", Map.of("date", "2026-11-06"));

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .contains("\"dayOfWeek\":\"금\"", "\"name\":\"운영체제\"", "\"name\":\"자료구조\"",
                        "\"lastClassEndAt\":\"2026-11-06T14:50:00+09:00\"",
                        "\"searchFromAt\":\"2026-11-06T15:30:00+09:00\"", "\"bufferMinutes\":30")
                .doesNotContain("AI응용프로젝트", "NO_SCHEDULE");
        assertThat(text(result).indexOf("운영체제")).isLessThan(text(result).indexOf("자료구조"));
        core.verify(0, anyRequestedFor(anyUrl())); // core·학사 시스템을 부르지 않는다
    }

    @Test
    @DisplayName("시간표가 없으면 NO_SCHEDULE과 넣는 방법을 오류 아닌 결과로 돌려주고 시각은 비운다")
    void noSchedule() {
        // when
        CallToolResult result = call(student(), "my_schedule", Map.of("date", "2026-11-06"));

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"code\":\"NO_SCHEDULE\"", "[내 시간표]")
                .doesNotContain("searchFromAt\":\"");
    }

    @Test
    @DisplayName("시간표는 있는데 그날 수업이 없으면 빈 목록과 '수업이 없어요'")
    void noClassThatDay() throws Exception {
        // given
        Map<String, String> me = student();
        send("PUT", me, FRIDAY);

        // when
        CallToolResult result = call(me, "my_schedule", Map.of("date", "2026-11-07"));

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"classes\":[]", "수업이 없어요").doesNotContain("NO_SCHEDULE");
    }

    @Test
    @DisplayName("날짜를 안 주면 오늘(서울) 기준이다")
    void defaultsToToday() {
        // when
        CallToolResult result = call(student(), "my_schedule", Map.of());

        // then
        assertThat(text(result)).contains("\"date\":\"" + LocalDate.now(ZoneId.of("Asia/Seoul")) + "\"");
    }

    @Test
    @DisplayName("날짜 모양이 틀리면 오류로 형식을 안내한다")
    void badDate() {
        // when
        CallToolResult result = call(student(), "my_schedule", Map.of("date", "내일"));

        // then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("YYYY-MM-DD");
    }

    @Test
    @DisplayName("다른 학생의 시간표는 보이지 않는다 (학생 키별 저장)")
    void separatedByStudent() throws Exception {
        // given
        send("PUT", student(), FRIDAY);

        // when
        CallToolResult other = call(student(), "my_schedule", Map.of("date", "2026-11-06"));

        // then
        assertThat(text(other)).contains("NO_SCHEDULE").doesNotContain("자료구조");
    }

    @Test
    @DisplayName("JWT sub가 같으면 토큰이 바뀌어도 같은 시간표를 본다")
    void sameSubSameSchedule() throws Exception {
        // given
        String sub = UUID.randomUUID().toString();
        send("PUT", Map.of("Authorization", jwt(sub, 1)), FRIDAY);

        // when: 다시 로그인해 토큰이 바뀜
        CallToolResult result = call(Map.of("Authorization", jwt(sub, 2)), "my_schedule", Map.of("date", "2026-11-06"));

        // then
        assertThat(text(result)).contains("자료구조");
    }

    @Test
    @DisplayName("로그인 헤더가 없으면 REST는 401, 도구는 오류")
    void unauthorized() throws Exception {
        // given
        Map<String, String> noLogin = Map.of();

        // when
        HttpResponse<String> rest = send("GET", noLogin, null);
        CallToolResult result = call(noLogin, "my_schedule", Map.of());

        // then
        assertThat(rest.statusCode()).isEqualTo(401);
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("로그인이 필요합니다");
    }

    @Test
    @DisplayName("REST 응답의 수업 칸은 넣을 때와 같은 모양이다 (요일 MON, 시각 HH:mm). 화면이 받은 그대로 다시 넣는다")
    void restResponseSameShapeAsRequest() throws Exception {
        // given
        Map<String, String> me = student();

        // when
        HttpResponse<String> put = send("PUT", me, FRIDAY);
        HttpResponse<String> get = send("GET", me, null);

        // then: MONDAY·10:30:00으로 나가면 화면이 요일을 못 알아봐 "수업 없음"으로 보이고, 고쳐 저장하면 "요일을 골라 주세요"
        for (HttpResponse<String> res : java.util.List.of(put, get)) {
            assertThat(res.body())
                    .contains("{\"dayOfWeek\":\"MON\",\"startTime\":\"10:30\",\"endTime\":\"12:00\",\"name\":\"AI응용프로젝트\"}")
                    .contains("\"dayOfWeek\":\"FRI\"")
                    .doesNotContain("MONDAY", "FRIDAY", ":00:00\"");
        }
        // 받은 그대로 다시 넣어도 통과한다
        assertThat(send("PUT", me, get.body()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("REST: 넣기 → 보기(요일·시작 순, 캐시 안 함) → 지우기 → 404 NO_SCHEDULE")
    void restRoundTrip() throws Exception {
        // given
        Map<String, String> me = student();

        // when
        HttpResponse<String> put = send("PUT", me, FRIDAY);
        HttpResponse<String> get = send("GET", me, null);
        HttpResponse<String> delete = send("DELETE", me, null);
        HttpResponse<String> after = send("GET", me, null);

        // then
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(put.body()).contains("\"updatedAt\":\"").contains("+09:00");
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        assertThat(get.body().indexOf("AI응용프로젝트")).isLessThan(get.body().indexOf("운영체제"));
        assertThat(delete.statusCode()).isEqualTo(204);
        assertThat(after.statusCode()).isEqualTo(404);
        assertThat(after.body()).contains("\"code\":\"NO_SCHEDULE\"");
    }

    @Test
    @DisplayName("REST: 같은 요일 겹침은 409가 아니라 400 SCHEDULE_OVERLAP, 저장하지 않는다")
    void rejectsOverlap() throws Exception {
        // given
        Map<String, String> me = student();
        String overlap = """
                {"classes": [
                  {"dayOfWeek": "FRI", "startTime": "13:00", "endTime": "14:50", "name": "자료구조"},
                  {"dayOfWeek": "FRI", "startTime": "14:00", "endTime": "15:00", "name": "운영체제"}]}""";

        // when
        HttpResponse<String> put = send("PUT", me, overlap);

        // then
        assertThat(put.statusCode()).isEqualTo(400);
        assertThat(put.body()).contains("SCHEDULE_OVERLAP");
        assertThat(send("GET", me, null).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("REST: 요일·시각·과목명·빈 목록 모양이 틀리면 400 INVALID_REQUEST")
    void rejectsBadInput() throws Exception {
        // given
        Map<String, String> me = student();

        // when
        HttpResponse<String> badDay = send("PUT", me, one("월", "09:00", "10:00", "a"));
        HttpResponse<String> badTime = send("PUT", me, one("MON", "9시", "10:00", "a"));
        HttpResponse<String> endBeforeStart = send("PUT", me, one("MON", "10:00", "09:00", "a"));
        HttpResponse<String> blankName = send("PUT", me, one("MON", "09:00", "10:00", "  "));
        HttpResponse<String> empty = send("PUT", me, "{\"classes\":[]}");

        // then
        assertThat(badDay.body()).contains("INVALID_REQUEST", "MON~SUN");
        assertThat(badTime.body()).contains("HH:mm");
        assertThat(endBeforeStart.body()).contains("시작보다 늦어야");
        assertThat(blankName.body()).contains("과목명은 1~30자");
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat(empty.body()).contains("하나 이상");
        assertThat(send("GET", me, null).statusCode()).isEqualTo(404); // 하나도 저장되지 않음
    }

    static String one(String day, String start, String end, String name) {
        return "{\"classes\":[{\"dayOfWeek\":\"%s\",\"startTime\":\"%s\",\"endTime\":\"%s\",\"name\":\"%s\"}]}"
                .formatted(day, start, end, name);
    }

    /** 서명은 보지 않으니 가짜 서명으로 충분하다 (게이트웨이가 확인) */
    static String jwt(String sub, int iat) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"sub\":\"" + sub + "\",\"iat\":" + iat + "}").getBytes(StandardCharsets.UTF_8));
        return "Bearer " + header + "." + payload + ".sig";
    }

    /** core 하루치: 1번 방만, 그날 전부 비어 있음 */
    static String emptyDay(String date) {
        StringBuilder slots = new StringBuilder();
        for (int i = 0; i < 48; i++) {
            java.time.LocalTime t = java.time.LocalTime.MIDNIGHT.plusMinutes(30L * i);
            String end = i == 47 ? LocalDate.parse(date).plusDays(1) + "T00:00:00+09:00" : date + "T" + t.plusMinutes(30) + ":00+09:00";
            slots.append(i > 0 ? "," : "").append("{\"startAt\":\"").append(date).append("T").append(t)
                    .append(":00+09:00\",\"endAt\":\"").append(end).append("\",\"available\":true}");
        }
        return "{\"date\":\"" + date + "\",\"slotMinutes\":30,\"rooms\":[{\"roomId\":1,\"roomName\":\"1번 방\",\"minPeople\":3,\"maxPeople\":5,\"slots\":["
                + slots + "]}]}";
    }

    @Test
    @DisplayName("week_plan은 7일을 한 번에: 날마다 core 하루치를 조회해 수업 끝난 뒤 가장 이른 칸을 준다")
    void weekPlan() throws Exception {
        // given
        Map<String, String> me = student();
        send("PUT", me, FRIDAY);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (int i = 0; i < 7; i++) {
            String date = today.plusDays(i).toString();
            core.stubFor(get(urlPathEqualTo("/api/rooms/availability")).withQueryParam("date", com.github.tomakehurst.wiremock.client.WireMock.equalTo(date))
                    .willReturn(okJson(emptyDay(date))));
        }

        // when
        Tool tool = connect(me).listTools().tools().stream().filter(t -> t.name().equals("week_plan")).findFirst().orElseThrow();
        CallToolResult result = call(me, "week_plan", Map.of("minutes", 90));

        // then
        assertThat(tool.annotations().readOnlyHint()).isTrue();
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"minutes\":90", "\"days\":[", "\"roomName\":\"1번 방\"").doesNotContain("NO_SCHEDULE");
        core.verify(7, getRequestedFor(urlPathEqualTo("/api/rooms/availability")));
    }
}
