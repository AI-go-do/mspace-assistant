package kr.ac.chosun.mspace.assistant.agent.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.ollama.OllamaLlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.ollama.OllamaProperties;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmReply;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolCall;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Ollama 어댑터: 요청 모양과 응답 해석 (가짜 Ollama = WireMock) */
class OllamaLlmClientTest {

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final ToolSpec FIND = new ToolSpec("find_available_rooms", "빈방 찾기",
            Map.of("type", "object", "properties", Map.of("date", Map.of("type", "string"))));

    OllamaLlmClient client(Duration timeout) {
        return new OllamaLlmClient(new OllamaProperties(ollama.baseUrl(), "qwen2.5:7b", timeout));
    }

    @Test
    @DisplayName("도구 호출 응답 → ToolCall(이름·인자), 요청에는 system 메시지·tools·stream=false")
    void parsesToolCall() {
        ollama.stubFor(post("/api/chat").willReturn(okJson("""
                {"message":{"role":"assistant","content":"",
                  "tool_calls":[{"function":{"name":"find_available_rooms","arguments":{"date":"2026-11-03"}}}]},
                 "done":true}""")));

        LlmReply reply = client(Duration.ofSeconds(2)).chat("지시문", List.of(new LlmMessage.User("내일 빈방")), List.of(FIND));

        assertThat(reply.wantsTools()).isTrue();
        assertThat(reply.toolCalls().getFirst().name()).isEqualTo("find_available_rooms");
        assertThat(reply.toolCalls().getFirst().arguments()).containsEntry("date", "2026-11-03");
        ollama.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("qwen2.5:7b")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("false")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("find_available_rooms"))));
    }

    @Test
    @DisplayName("도구 결과는 role=tool, tool_name으로 돌려보낸다")
    void sendsToolResult() {
        ollama.stubFor(post("/api/chat").willReturn(okJson("{\"message\":{\"role\":\"assistant\",\"content\":\"2번 방이 비어요\"}}")));
        var call = new ToolCall("x", "find_available_rooms", Map.of("date", "2026-11-03"));

        LlmReply reply = client(Duration.ofSeconds(2)).chat("지시문", List.of(
                new LlmMessage.User("내일 빈방"),
                new LlmMessage.Assistant("", List.of(call)),
                new LlmMessage.ToolResult("x", "find_available_rooms", "{\"rooms\":[]}", false)), List.of(FIND));

        assertThat(reply.text()).isEqualTo("2번 방이 비어요");
        assertThat(reply.wantsTools()).isFalse();
        ollama.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.messages[2].tool_calls[0].function.name", equalTo("find_available_rooms")))
                .withRequestBody(matchingJsonPath("$.messages[3].role", equalTo("tool")))
                .withRequestBody(matchingJsonPath("$.messages[3].tool_name", equalTo("find_available_rooms"))));
    }

    @Test
    @DisplayName("Ollama 500이나 응답 지연 → LLM_UNAVAILABLE")
    void unavailable() {
        ollama.stubFor(post("/api/chat").willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).chat("s", List.of(new LlmMessage.User("a")), List.of()))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo("LLM_UNAVAILABLE"));

        ollama.stubFor(post("/api/chat").willReturn(okJson("{\"message\":{}}").withFixedDelay(1_500)));
        assertThatThrownBy(() -> client(Duration.ofMillis(500)).chat("s", List.of(new LlmMessage.User("a")), List.of()))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.code()).isEqualTo("LLM_UNAVAILABLE"));
    }
}
