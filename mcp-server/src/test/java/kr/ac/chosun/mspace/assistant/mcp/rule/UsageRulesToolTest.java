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
import kr.ac.chosun.mspace.assistant.mcp.McpToolTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        // when
        Tool tool = connect(USER).listTools().tools().stream()
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

    @Test
    @DisplayName("core 응답에 reservation이 없으면 규칙을 지어내지 않게 오류로 안내한다")
    void missingReservationIsError() {
        // given
        core.stubFor(get("/api/rules").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));

        // when
        CallToolResult result = call(USER, "usage_rules", Map.of());

        // then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("이용 규칙을 불러오지 못했어요");
    }

    @Test
    @DisplayName("core 응답에 숫자가 하나라도 빠지면 0으로 안내하지 않고 오류로 안내한다")
    void missingNumberIsError() {
        // given: noShowMinutes가 빠진 응답
        core.stubFor(get("/api/rules").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"reservation":{"bookingWindowDays":7,"slotMinutes":30,"maxMinutes":120,
                          "checkinOpenBeforeMinutes":10,"instantNoShowMinutes":10,
                          "extension":{"maxCount":1,"openBeforeMinutes":10,"maxMinutes":120,"chainGapMinutes":30}},
                         "conduct":[{"key":"FOOD","title":"음식","rule":"r","penalty":"p"}],
                         "reminders":{"checkin":"a","checkout":"b"}}""")));

        // when
        CallToolResult result = call(USER, "usage_rules", Map.of());

        // then
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("이용 규칙을 불러오지 못했어요");
    }

    @Test
    @DisplayName("숫자는 다 있어도 이용 수칙(conduct)이 빠지면 규칙 없이 답하지 않고 오류로 알린다")
    void missingConductIsError() {
        // given: conduct가 없는 응답
        core.stubFor(get("/api/rules").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"reservation":{"bookingWindowDays":7,"slotMinutes":30,"maxMinutes":120,
                          "checkinOpenBeforeMinutes":10,"noShowMinutes":15,"instantNoShowMinutes":10,
                          "extension":{"maxCount":1,"openBeforeMinutes":10,"maxMinutes":120,"chainGapMinutes":30}},
                         "reminders":{"checkin":"a","checkout":"b"}}""")));

        // when
        CallToolResult result = call(USER, "usage_rules", Map.of());

        // then
        assertThat(result.isError()).isTrue();
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
    }
}
