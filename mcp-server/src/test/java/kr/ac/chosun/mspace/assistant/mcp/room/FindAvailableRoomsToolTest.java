package kr.ac.chosun.mspace.assistant.mcp.room;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.LocalTime;
import java.util.Map;
import java.util.StringJoiner;
import kr.ac.chosun.mspace.assistant.mcp.McpToolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** find_available_rooms를 진짜 MCP 클라이언트로 부른다. 코어는 WireMock */
class FindAvailableRoomsToolTest extends McpToolTestSupport {

    static final Map<String, String> USER = Map.of("X-User-Id", "1");

    /** core RoomAvailabilityService.DayAvailability 모양. 1번 방은 14:00–16:00 예약됨, 2번 방은 종일 빔 */
    static String coreDay() {
        StringJoiner rooms = new StringJoiner(",");
        rooms.add(room(1, 3, 5, "14:00", "16:00"));
        rooms.add(room(2, 3, 5, null, null));
        return "{\"date\":\"2026-11-03\",\"slotMinutes\":30,\"rooms\":[" + rooms + "]}";
    }

    static String room(int id, int min, int max, String busyFrom, String busyTo) {
        StringJoiner slots = new StringJoiner(",");
        for (int i = 0; i < 48; i++) {
            LocalTime s = LocalTime.MIDNIGHT.plusMinutes(30L * i);
            String end = i == 47 ? "2026-11-04T00:00:00+09:00" : "2026-11-03T" + s.plusMinutes(30) + ":00+09:00";
            boolean busy = busyFrom != null && !s.isBefore(LocalTime.parse(busyFrom)) && s.isBefore(LocalTime.parse(busyTo));
            slots.add("{\"startAt\":\"2026-11-03T" + s + ":00+09:00\",\"endAt\":\"" + end + "\",\"available\":" + !busy + "}");
        }
        return "{\"roomId\":" + id + ",\"roomName\":\"" + id + "번 방\",\"minPeople\":" + min
                + ",\"maxPeople\":" + max + ",\"slots\":[" + slots + "]}";
    }

    @Test
    @DisplayName("도구 목록에 find_available_rooms가 읽기 전용, date 필수로 보인다")
    void listsTool() {
        Tool tool = connect(USER).listTools().tools().stream()
                .filter(t -> t.name().equals("find_available_rooms")).findFirst().orElseThrow();

        assertThat(tool.annotations().readOnlyHint()).isTrue();
        assertThat((java.util.List<Object>) tool.inputSchema().get("required")).containsExactly("date");
        assertThat((Map<Object, Object>) tool.inputSchema().get("properties")).containsKeys("date", "from", "to", "headcount");
    }

    @Test
    @DisplayName("내일 오후 2시 빈방 → 2번 방만, 빈 구간은 서울 시간으로, 사용자 헤더 전달")
    void findsFreeRooms() {
        // given
        core.stubFor(get(urlPathEqualTo("/api/rooms/availability")).withQueryParam("date", equalTo("2026-11-03"))
                .willReturn(okJson(coreDay())));

        // when
        CallToolResult result = call(USER, "find_available_rooms",
                Map.of("date", "2026-11-03", "from", "14:00", "to", "16:00", "headcount", 4));

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"roomId\":2", "2026-11-03T00:00:00+09:00").doesNotContain("\"roomId\":1");
        core.verify(getRequestedFor(urlPathEqualTo("/api/rooms/availability")).withHeader("X-User-Id", equalTo("1")));
    }

    @Test
    @DisplayName("조회 범위 밖 날짜 → 코어의 400 문장을 그대로 오류 결과로")
    void outOfRange() {
        core.stubFor(get(urlPathEqualTo("/api/rooms/availability")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"ROOM_DATE_OUT_OF_RANGE\",\"message\":\"오늘부터 예약 가능한 기간 안의 날짜만 조회할 수 있습니다\"}")));

        CallToolResult result = call(USER, "find_available_rooms", Map.of("date", "2026-12-25"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("예약 가능한 기간 안의 날짜만");
    }

    @Test
    @DisplayName("날짜 형식이 틀리면 코어를 부르지 않고 형식 오류")
    void invalidDate() {
        CallToolResult result = call(USER, "find_available_rooms", Map.of("date", "내일"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("YYYY-MM-DD");
        core.verify(0, getRequestedFor(urlPathEqualTo("/api/rooms/availability")));
    }

    @Test
    @DisplayName("사용자 헤더 없이 부르면 로그인 필요")
    void noCredential() {
        CallToolResult result = call(Map.of(), "find_available_rooms", Map.of("date", "2026-11-03"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("로그인이 필요합니다");
    }
}
