package kr.ac.chosun.mspace.assistant.mcp.campus;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.mcp.McpToolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 학교 생활 도구(11차 회의): 날씨는 가짜 예보 서버(WireMock /v1/forecast), 셔틀은 테스트용 시간표(McpToolTestSupport),
 * 식당 혼잡도는 연결 전. 예약 코어(/api/…)는 부르지 않는다.
 */
class CampusToolsTest extends McpToolTestSupport {

    static final Map<String, String> USER = Map.of("X-User-Id", "1");

    static String forecast(LocalDate today) {
        return """
                {"current": {"time": "%sT14:00", "temperature_2m": 12.4, "weather_code": 3},
                 "daily": {"time": ["%s", "%s"], "weather_code": [61, 0],
                   "temperature_2m_max": [15.2, 17.0], "temperature_2m_min": [8.1, 6.3],
                   "precipitation_probability_max": [70, 10]}}""".formatted(today, today, today.plusDays(1));
    }

    static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    @Test
    @DisplayName("도구 목록에 campus_weather·campus_shuttle·cafeteria_crowd·city_bus_arrival이 읽기 전용으로 보인다")
    void listsTools() {
        // when
        List<Tool> tools = connect(USER).listTools().tools().stream()
                .filter(t -> List.of("campus_weather", "campus_shuttle", "cafeteria_crowd", "city_bus_arrival").contains(t.name()))
                .toList();

        // then
        assertThat(tools).hasSize(4);
        assertThat(tools).allSatisfy(t -> assertThat(t.annotations().readOnlyHint()).isTrue());
    }

    @Test
    @DisplayName("날씨: 오늘 예보를 한국어로, 비 올 확률 70%면 우산. 학교 좌표만 보내고 학생 헤더는 보내지 않는다")
    void weatherToday() {
        // given
        core.stubFor(get(urlPathEqualTo("/v1/forecast")).willReturn(okJson(forecast(today()))));

        // when
        CallToolResult result = call(USER, "campus_weather", Map.of());

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"summary\":\"비\"", "\"rainChance\":70", "\"umbrella\":true",
                "\"nowTemperature\":12.4", "\"nowSummary\":\"흐림\"", "Open-Meteo");
        core.verify(getRequestedFor(urlPathEqualTo("/v1/forecast"))
                .withQueryParam("latitude", equalTo("35.1408"))
                .withQueryParam("timezone", equalTo("Asia/Seoul"))
                .withoutHeader("X-User-Id"));
    }

    @Test
    @DisplayName("날씨: 내일은 지금 기온 없이 그날 예보만. 비 올 확률이 낮으면 우산 false")
    void weatherTomorrow() {
        // given
        core.stubFor(get(urlPathEqualTo("/v1/forecast")).willReturn(okJson(forecast(today()))));

        // when
        CallToolResult result = call(USER, "campus_weather", Map.of("date", today().plusDays(1).toString()));

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"summary\":\"맑음\"", "\"umbrella\":false").doesNotContain("12.4");
    }

    @Test
    @DisplayName("날씨: 예보 서버가 오류면 지어내지 않고 오류 문장")
    void weatherDown() {
        // given
        core.stubFor(get(urlPathEqualTo("/v1/forecast")).willReturn(aResponse().withStatus(500)));

        // when
        CallToolResult result = call(USER, "campus_weather", Map.of());

        // then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("날씨를 불러오지 못했어요");
    }

    @Test
    @DisplayName("날씨: 모레 이후는 부르지 않고 오늘·내일만 된다고 답한다")
    void weatherOutOfRange() {
        // when
        CallToolResult result = call(USER, "campus_weather", Map.of("date", today().plusDays(3).toString()));

        // then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("오늘과 내일만");
        core.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("셔틀: 정한 날 12:00 뒤 출발 3개와 마지막 차, 시간표 기준 문장을 함께")
    void shuttleNext() {
        // given: 2026-11-16은 월요일
        Map<String, Object> args = Map.of("date", "2026-11-16", "after", "12:00");

        // when
        CallToolResult result = call(USER, "campus_shuttle", args);

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"next\":[\"12:10\",\"13:00\",\"18:00\"]", "\"last\":\"18:00\"",
                "실시간 위치가 아니라");
        core.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("셔틀: 주말은 운행 노선이 없다고 답한다")
    void shuttleWeekend() {
        // when: 2026-11-15는 일요일
        CallToolResult result = call(USER, "campus_shuttle", Map.of("date", "2026-11-15"));

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("등록된 셔틀 운행이 없어요");
    }

    @Test
    @DisplayName("식당 혼잡도: 아직 연결 전이라 NOT_CONNECTED와 안내 문장")
    void cafeteriaNotConnected() {
        // when
        CallToolResult result = call(USER, "cafeteria_crowd", Map.of());

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("NOT_CONNECTED", "아직 볼 수 없어요");
    }

    @Test
    @DisplayName("시내버스: 정류소 ID로 도착 정보를 받아 남은 분 순으로. 키는 보내고 학생 헤더는 보내지 않는다")
    void cityBus() {
        // given
        core.stubFor(get(urlPathEqualTo("/arriveInfo")).willReturn(okJson("""
                {"RESULT":{"RESULT_CODE":"SUCCESS"},"ROW_COUNT":2,"BUSSTOP_LIST":[
                 {"LINE_NAME":"57","REMAIN_MIN":9,"REMAIN_STOP":4},
                 {"LINE_NAME":"419","REMAIN_MIN":"3","REMAIN_STOP":"1"}]}""")));

        // when
        CallToolResult result = call(USER, "city_bus_arrival", Map.of());

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"arsId\":\"1177\"", "\"line\":\"419\",\"minutes\":3,\"stops\":1", "광주 버스정보 기준");
        assertThat(text(result).indexOf("\"419\"")).isLessThan(text(result).indexOf("\"57\""));
        core.verify(getRequestedFor(urlPathEqualTo("/arriveInfo")).withQueryParam("BUSSTOP_ID", equalTo("2873"))
                .withQueryParam("serviceKey", equalTo("test-key")).withoutHeader("Authorization"));
    }

    @Test
    @DisplayName("시내버스: API가 실패하면 도착 시간을 지어내지 않고 BUS_UNAVAILABLE과 정류장 번호")
    void cityBusDown() {
        // given
        core.stubFor(get(urlPathEqualTo("/arriveInfo")).willReturn(serverError()));

        // when
        CallToolResult result = call(USER, "city_bus_arrival", Map.of());

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("BUS_UNAVAILABLE", "1177").doesNotContain("\"minutes\"");
    }

    @Test
    @DisplayName("로그인 정보가 없으면 날씨도 부르지 않는다 (다른 도구와 같게)")
    void requiresLogin() {
        // when
        CallToolResult result = call(Map.of(), "campus_weather", Map.of());

        // then
        assertThat(result.isError()).isTrue();
        core.verify(0, anyRequestedFor(anyUrl()));
    }
}
