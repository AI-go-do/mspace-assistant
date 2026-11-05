package kr.ac.chosun.mspace.assistant.mcp.room;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.mcp.McpToolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** room_status(FR-DEV-102)를 진짜 MCP 클라이언트로 부른다. 코어는 WireMock, 시계는 실제라 시각을 지금 기준으로 만든다 */
class RoomStatusToolTest extends McpToolTestSupport {

    static final Map<String, String> USER = Map.of("X-User-Id", "1");
    static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Test
    @DisplayName("도구 목록에 room_status가 읽기 전용, 입력 없음으로 보인다")
    void listsTool() {
        Tool tool = connect(USER).listTools().tools().stream()
                .filter(t -> t.name().equals("room_status")).findFirst().orElseThrow();

        assertThat(tool.annotations().readOnlyHint()).isTrue();
        assertThat(tool.description()).contains("지금 바로 쓸 수 있는 방");
    }

    @Test
    @DisplayName("비어 있는 방에는 지금 바로 쓸 때 고를 수 있는 가장 늦은 끝(latestEndAt)을 붙이고, 사용 중인 방은 언제 비는지 준다")
    void addsLatestEnd() {
        // given: 1번 방 사용 중(30분 뒤 끝), 2번 방 빔(다음 예약 없음), 3번 방 빔(1시간 뒤 예약)
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime in30 = now.plusMinutes(30);
        OffsetDateTime in60 = now.plusMinutes(60);
        core.stubFor(get("/api/rooms/status").willReturn(okJson("""
                {"now": "%s", "availableCount": 2, "roomCount": 3, "rooms": [
                  {"roomId": 1, "roomName": "1번 방", "minPeople": 3, "maxPeople": 5, "status": "IN_USE",
                   "currentStartAt": "%s", "currentEndAt": "%s", "nextStartAt": null, "presence": "OCCUPIED"},
                  {"roomId": 2, "roomName": "2번 방", "minPeople": 3, "maxPeople": 5, "status": "AVAILABLE",
                   "currentStartAt": null, "currentEndAt": null, "nextStartAt": null, "presence": "EMPTY"},
                  {"roomId": 3, "roomName": "3번 방", "minPeople": 3, "maxPeople": 5, "status": "AVAILABLE",
                   "currentStartAt": null, "currentEndAt": null, "nextStartAt": "%s", "presence": "UNKNOWN"}
                ]}""".formatted(now.format(ISO), now.minusMinutes(30).format(ISO), in30.format(ISO), in60.format(ISO)))));

        // when
        CallToolResult result = call(USER, "room_status", Map.of());

        // then
        assertThat(result.isError()).as(text(result)).isFalse();
        String text = text(result);
        assertThat(text).contains("\"availableCount\":2", "\"status\":\"IN_USE\"", "start_now_reservation");
        // 2번 방: 지금+2시간을 30분 칸으로 올린 시각, 3번 방: 다음 예약(1시간 뒤) 전까지
        assertThat(text).contains("\"roomId\":3").contains(in60.toLocalTime().toString().substring(0, 5));
        assertThat(text).doesNotContain("\"roomId\":2,\"roomName\":\"2번 방\",\"minPeople\":3,\"maxPeople\":5,\"status\":\"AVAILABLE\",\"currentEndAt\":null,\"nextStartAt\":null,\"latestEndAt\":null");
        core.verify(getRequestedFor(urlEqualTo("/api/rooms/status")).withHeader("X-User-Id", equalTo("1")));
    }

    @Test
    @DisplayName("바로 쓸 수 있는 방이 없으면 note로 알린다")
    void noneAvailable() {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.SECONDS);
        core.stubFor(get("/api/rooms/status").willReturn(okJson("""
                {"now": "%s", "availableCount": 0, "roomCount": 1, "rooms": [
                  {"roomId": 1, "roomName": "1번 방", "minPeople": 3, "maxPeople": 5, "status": "RESERVED",
                   "currentStartAt": "%s", "currentEndAt": "%s", "nextStartAt": null, "presence": "UNKNOWN"}
                ]}""".formatted(now.format(ISO), now.minusMinutes(5).format(ISO), now.plusMinutes(55).format(ISO)))));

        CallToolResult result = call(USER, "room_status", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"availableCount\":0", "지금 바로 쓸 수 있는 방이 없어요");
    }
}
