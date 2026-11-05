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
import java.util.Base64;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini.GeminiImageReader;
import kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini.GeminiProperties;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Gemini 그림 읽기 어댑터: inlineData 요청 모양과 오류 매핑 (가짜 Gemini = WireMock) */
class GeminiImageReaderTest {

    @RegisterExtension
    static WireMockExtension gemini = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    static final String PATH = "/v1beta/models/gemini-3.5-flash-lite:generateContent";
    static final byte[] IMAGE = {1, 2, 3, 4};

    GeminiImageReader reader(String key) {
        return new GeminiImageReader(new GeminiProperties(gemini.baseUrl(), null, key, Duration.ofSeconds(2)));
    }

    @Test
    @DisplayName("요청: 그림은 base64 inlineData로, 답은 JSON만(responseMimeType) 요구하고, 생각(thought) 조각은 버린다")
    void requestShape() {
        // given
        gemini.stubFor(post(PATH).willReturn(okJson("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"text":"생각 중","thought":true},
                  {"text":"{\\"classes\\":[]}"}]},"finishReason":"STOP"}]}""")));

        // when
        String text = reader("test-key").read("시간표를 JSON으로", "image/png", IMAGE);

        // then
        assertThat(text).isEqualTo("{\"classes\":[]}");
        gemini.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("x-goog-api-key", equalTo("test-key"))
                .withRequestBody(matchingJsonPath("$.systemInstruction.parts[0].text", equalTo("시간표를 JSON으로")))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].inlineData.mimeType", equalTo("image/png")))
                .withRequestBody(matchingJsonPath("$.contents[0].parts[0].inlineData.data",
                        equalTo(Base64.getEncoder().encodeToString(IMAGE))))
                .withRequestBody(matchingJsonPath("$.generationConfig.responseMimeType", equalTo("application/json"))));
    }

    @Test
    @DisplayName("키가 없으면 부르지 않고 LLM_NOT_CONFIGURED")
    void noKey() {
        assertThatThrownBy(() -> reader(" ").read("i", "image/png", IMAGE))
                .isInstanceOf(LlmException.class).extracting("code").isEqualTo("LLM_NOT_CONFIGURED");
        assertThat(gemini.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("429는 LLM_RATE_LIMITED, 5xx·빈 답은 LLM_UNAVAILABLE")
    void errorMapping() {
        gemini.stubFor(post(PATH).willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> reader("k").read("i", "image/png", IMAGE))
                .isInstanceOf(LlmException.class).extracting("code").isEqualTo("LLM_RATE_LIMITED");

        gemini.stubFor(post(PATH).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> reader("k").read("i", "image/png", IMAGE))
                .isInstanceOf(LlmException.class).extracting("code").isEqualTo("LLM_UNAVAILABLE");

        gemini.stubFor(post(PATH).willReturn(okJson("{\"candidates\":[]}")));
        assertThatThrownBy(() -> reader("k").read("i", "image/png", IMAGE))
                .isInstanceOf(LlmException.class).extracting("code").isEqualTo("LLM_UNAVAILABLE");
    }
}
