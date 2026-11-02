package kr.ac.chosun.mspace.assistant.agent.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini.GeminiLlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini.GeminiProperties;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmReply;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolCall;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.json.JsonMapper;

/** Gemini 어댑터: 요청 모양과 응답 해석 (가짜 Gemini = WireMock, 진짜 키 없이) */
class GeminiLlmClientTest {

    @RegisterExtension
    static WireMockExtension gemini = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String PATH = "/v1beta/models/gemini-2.5-flash:generateContent";
    static final ToolSpec FIND = new ToolSpec("find_available_rooms", "빈방 찾기",
            Map.of("type", "object", "properties", Map.of("date", Map.of("type", "string")), "required", List.of("date")));

    GeminiLlmClient client(String key) {
        return new GeminiLlmClient(new GeminiProperties(gemini.baseUrl(), null, key, Duration.ofSeconds(2)),
                JsonMapper.builder().build());
    }

    @Test
    @DisplayName("요청: 키는 헤더로, 지시문·도구 스키마(parametersJsonSchema)를 함께 보낸다")
    void requestShape() {
        gemini.stubFor(post(PATH).willReturn(okJson("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"안녕하세요"}]},"finishReason":"STOP"}]}""")));

        LlmReply reply = client("test-key").chat("너는 M-SPACE 비서다", List.of(new LlmMessage.User("빈방 있어?")), List.of(FIND));

        assertThat(reply.text()).isEqualTo("안녕하세요");
        assertThat(reply.wantsTools()).isFalse();
        gemini.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("x-goog-api-key", equalTo("test-key"))
                .withRequestBody(matchingJsonPath("$.systemInstruction.parts[0].text", equalTo("너는 M-SPACE 비서다")))
                .withRequestBody(matchingJsonPath("$.contents[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.tools[0].functionDeclarations[0].name", equalTo("find_available_rooms")))
                .withRequestBody(matchingJsonPath("$.tools[0].functionDeclarations[0].parametersJsonSchema.required[0]", equalTo("date"))));
    }

    @Test
    @DisplayName("응답의 functionCall은 도구 호출로, thoughtSignature도 함께 보관한다. 생각(thought) 문장은 버린다")
    void parsesFunctionCall() {
        gemini.stubFor(post(PATH).willReturn(okJson("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"text":"내부 생각","thought":true},
                  {"functionCall":{"name":"find_available_rooms","args":{"date":"2026-11-03"}},"thoughtSignature":"sig-1"}
                ]}}]}""")));

        LlmReply reply = client("k").chat("s", List.of(new LlmMessage.User("내일 빈방")), List.of(FIND));

        assertThat(reply.text()).isEmpty();
        assertThat(reply.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("find_available_rooms");
            assertThat(c.arguments()).containsEntry("date", "2026-11-03");
            assertThat(c.signature()).isEqualTo("sig-1");
            assertThat(c.id()).isNotBlank();
        });
    }

    @Test
    @DisplayName("도구 결과는 functionResponse(result 객체)로, 앞선 호출은 서명과 함께 model 차례로 다시 보낸다")
    void sendsToolResultBack() {
        gemini.stubFor(post(PATH).willReturn(okJson("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"2번 방이 비어 있어요"}]}}]}""")));
        var call = new ToolCall("c1", "find_available_rooms", Map.of("date", "2026-11-03"), "sig-1");

        client("k").chat("s", List.of(
                new LlmMessage.User("내일 빈방"),
                new LlmMessage.Assistant("", List.of(call)),
                new LlmMessage.ToolResult("c1", "find_available_rooms", "{\"rooms\":[{\"roomId\":2}]}", false)), List.of(FIND));

        gemini.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"contents":[
                  {"role":"user","parts":[{"text":"내일 빈방"}]},
                  {"role":"model","parts":[{"functionCall":{"name":"find_available_rooms","args":{"date":"2026-11-03"}},"thoughtSignature":"sig-1"}]},
                  {"role":"user","parts":[{"functionResponse":{"name":"find_available_rooms","response":{"result":{"rooms":[{"roomId":2}]}}}}]}
                ]}""", true, true)));
    }

    @Test
    @DisplayName("429(호출 한도) → 잠시 후 다시·예약 화면 안내")
    void rateLimited() {
        gemini.stubFor(post(PATH).willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> client("k").chat("s", List.of(new LlmMessage.User("hi")), List.of()))
                .isInstanceOf(LlmException.class).extracting("code").isEqualTo("LLM_RATE_LIMITED");
    }

    @Test
    @DisplayName("키가 없으면 Gemini를 부르지 않고 '준비되지 않았어요'")
    void notConfigured() {
        assertThatThrownBy(() -> client("").chat("s", List.of(new LlmMessage.User("hi")), List.of()))
                .isInstanceOf(LlmException.class).extracting("code").isEqualTo("LLM_NOT_CONFIGURED");
        gemini.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("응답이 늦으면(타임아웃 2초) 응답 없음으로 끝난다")
    void timeout() {
        gemini.stubFor(post(PATH).willReturn(okJson("{}").withFixedDelay(3_000)));

        assertThatThrownBy(() -> client("k").chat("s", List.of(new LlmMessage.User("hi")), List.of()))
                .isInstanceOf(LlmException.class).extracting("code").isEqualTo("LLM_UNAVAILABLE");
    }

    @Test
    @DisplayName("한 번에 도구 두 개를 부르면 결과 두 개를 한 user 차례에 묶어 보낸다")
    void groupsParallelToolResults() {
        // given
        gemini.stubFor(post(PATH).willReturn(okJson("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"정리했어요"}]}}]}""")));
        var find = new ToolCall("c1", "find_available_rooms", Map.of("date", "2026-11-03"), "sig-1");
        var mine = new ToolCall("c2", "my_reservations", Map.of(), null);

        // when
        client("k").chat("s", List.of(
                new LlmMessage.User("내일 빈방이랑 내 예약"),
                new LlmMessage.Assistant("", List.of(find, mine)),
                new LlmMessage.ToolResult("c1", "find_available_rooms", "{\"rooms\":[]}", false),
                new LlmMessage.ToolResult("c2", "my_reservations", "{\"reservations\":[]}", false)), List.of(FIND));

        // then
        gemini.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"contents":[
                  {"role":"user","parts":[{"text":"내일 빈방이랑 내 예약"}]},
                  {"role":"model","parts":[
                    {"functionCall":{"name":"find_available_rooms","args":{"date":"2026-11-03"}},"thoughtSignature":"sig-1"},
                    {"functionCall":{"name":"my_reservations","args":{}}}]},
                  {"role":"user","parts":[
                    {"functionResponse":{"name":"find_available_rooms","response":{"result":{"rooms":[]}}}},
                    {"functionResponse":{"name":"my_reservations","response":{"result":{"reservations":[]}}}}]}
                ]}""", true, true)));
    }

    @Test
    @DisplayName("도구 인자에 null 값이 있어도 깨지지 않는다")
    void nullArgumentValue() {
        // given
        var args = new java.util.HashMap<String, Object>();
        args.put("date", null);

        // when
        var call = new ToolCall("c1", "find_available_rooms", args);

        // then
        assertThat(call.arguments()).containsEntry("date", null);
    }

    @Test
    @DisplayName("응답 본문이 JSON이 아니면 응답 없음으로 끝난다")
    void unreadableBody() {
        // given
        gemini.stubFor(post(PATH).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"candidates\": [")));

        // when / then
        assertThatThrownBy(() -> client("k").chat("s", List.of(new LlmMessage.User("hi")), List.of()))
                .isInstanceOf(LlmException.class).extracting("code").isEqualTo("LLM_UNAVAILABLE");
    }

    @Test
    @DisplayName("Gemini가 준 호출 id는 functionCall·functionResponse에 그대로 돌려보내고, 어댑터가 만든 id는 보내지 않는다")
    void echoesGeminiCallIds() {
        // given
        gemini.stubFor(post(PATH).willReturn(okJson("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"됐어요"}]}}]}""")));
        var a = new ToolCall("g-1", "find_available_rooms", Map.of("date", "2026-11-03"));
        var b = new ToolCall("local_x", "find_available_rooms", Map.of("date", "2026-11-04"));

        // when
        client("k").chat("s", List.of(
                new LlmMessage.User("내일·모레 빈방"),
                new LlmMessage.Assistant("", List.of(a, b)),
                new LlmMessage.ToolResult("g-1", "find_available_rooms", "{}", false),
                new LlmMessage.ToolResult("local_x", "find_available_rooms", "{}", false)), List.of(FIND));

        // then
        gemini.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.contents[1].parts[0].functionCall.id", equalTo("g-1")))
                .withRequestBody(matchingJsonPath("$.contents[2].parts[0].functionResponse.id", equalTo("g-1")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*local_x.*")));
    }
}
