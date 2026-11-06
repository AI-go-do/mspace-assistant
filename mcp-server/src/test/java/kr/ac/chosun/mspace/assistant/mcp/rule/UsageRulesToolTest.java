package kr.ac.chosun.mspace.assistant.mcp.rule;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;
import java.util.stream.Stream;
import kr.ac.chosun.mspace.assistant.mcp.McpToolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** usage_rules를 진짜 MCP 클라이언트로 부른다. 코어 GET /api/rules(core #57)는 WireMock */
class UsageRulesToolTest extends McpToolTestSupport {

    static final Map<String, String> USER = Map.of("X-User-Id", "1");

    /** core RulesService.Rules 모양 (core #57 application.yml mspace.rules 일부) */
    static final String RULES = """
            {"reservation": {"bookingWindowDays": 7, "slotMinutes": 30, "maxMinutes": 120,
               "checkinOpenBeforeMinutes": 10, "noShowMinutes": 15, "instantNoShowMinutes": 10,
               "extension": {"maxCount": 1, "openBeforeMinutes": 10, "maxMinutes": 120, "chainGapMinutes": 30}},
             "conduct": [
               {"key": "FOOD", "title": "음식", "rule": "음식물 반입 금지예요. 먹을 것과 마실 것(음료 포함) 모두 가져올 수 없어요",
                "penalty": "신고가 사실로 판정되면 경고. 기기·비품이 고장 나면 배상을 요구해요"},
               {"key": "NO_SHOW", "title": "노쇼", "rule": "시작 15분 안에 키패드로 입실해요(지금 바로 쓰기는 10분)",
                "penalty": "예약이 자동으로 풀려요. 30일 안에 2번이면 14일 동안 예약·참여 모두 못 해요"}],
             "reminders": {"checkin": "음식물은 반입 금지예요", "checkout": "자리 정리하셨나요?"}}""";

    @Test
    @DisplayName("도구 목록에 usage_rules가 읽기 전용, 입력 없이 보인다")
    void listsTool() {
        // given
        var client = connect(USER);

        // when
        Tool tool = client.listTools().tools().stream()
                .filter(t -> t.name().equals("usage_rules")).findFirst().orElseThrow();

        // then
        assertThat(tool.annotations().readOnlyHint()).isTrue();
        assertThat(tool.inputSchema().get("required")).satisfiesAnyOf(
                r -> assertThat(r).isNull(), r -> assertThat((java.util.List<Object>) r).isEmpty());
    }

    @Test
    @DisplayName("규칙을 core에서 받아 숫자·수칙·안내를 그대로 돌려주고, 학생 헤더를 넘긴다")
    void returnsRules() {
        // given
        core.stubFor(get("/api/rules").willReturn(okJson(RULES)));

        // when
        CallToolResult result = call(USER, "usage_rules", Map.of());

        // then
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"bookingWindowDays\":7", "\"maxCount\":1", "음식물 반입 금지예요",
                "30일 안에 2번이면 14일", "자리 정리하셨나요?");
        core.verify(getRequestedFor(urlEqualTo("/api/rules")).withHeader("X-User-Id", equalTo("1")));
    }

    static Stream<Arguments> brokenRules() {
        return Stream.of(
                Arguments.of("reservation이 없음", "{}"),
                Arguments.of("숫자(noShowMinutes)가 하나 빠짐", """
                        {"reservation":{"bookingWindowDays":7,"slotMinutes":30,"maxMinutes":120,
                          "checkinOpenBeforeMinutes":10,"instantNoShowMinutes":10,
                          "extension":{"maxCount":1,"openBeforeMinutes":10,"maxMinutes":120,"chainGapMinutes":30}},
                         "conduct":[{"key":"FOOD","title":"음식","rule":"r","penalty":"p"}],
                         "reminders":{"checkin":"a","checkout":"b"}}"""),
                Arguments.of("이용 수칙(conduct)이 빠짐", """
                        {"reservation":{"bookingWindowDays":7,"slotMinutes":30,"maxMinutes":120,
                          "checkinOpenBeforeMinutes":10,"noShowMinutes":15,"instantNoShowMinutes":10,
                          "extension":{"maxCount":1,"openBeforeMinutes":10,"maxMinutes":120,"chainGapMinutes":30}},
                         "reminders":{"checkin":"a","checkout":"b"}}"""),
                Arguments.of("수칙 한 줄에 내용(rule)이 빠짐", """
                        {"reservation":{"bookingWindowDays":7,"slotMinutes":30,"maxMinutes":120,
                          "checkinOpenBeforeMinutes":10,"noShowMinutes":15,"instantNoShowMinutes":10,
                          "extension":{"maxCount":1,"openBeforeMinutes":10,"maxMinutes":120,"chainGapMinutes":30}},
                         "conduct":[{"key":"FOOD","title":"음식","penalty":"p"}],
                         "reminders":{"checkin":"a","checkout":"b"}}"""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenRules")
    @DisplayName("core 응답에 규칙이 하나라도 빠지면(reservation·숫자·수칙·수칙 내용) 0이나 지어낸 규칙으로 안내하지 않고 오류로 알린다")
    void brokenRulesAreError(String missing, String body) {
        // given
        core.stubFor(get("/api/rules").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));

        // when
        CallToolResult result = call(USER, "usage_rules", Map.of());

        // then
        assertThat(result.isError()).as(missing).isTrue();
        assertThat(text(result)).contains("이용 규칙을 불러오지 못했어요");
    }

    @Test
    @DisplayName("core가 내려가 있으면 오류 문장을 그대로 전한다")
    void coreDown() {
        // given
        core.stubFor(get("/api/rules").willReturn(aResponse().withStatus(503)));

        // when
        CallToolResult result = call(USER, "usage_rules", Map.of());

        // then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("예약 서버에 연결하지 못했어요");
    }
}
