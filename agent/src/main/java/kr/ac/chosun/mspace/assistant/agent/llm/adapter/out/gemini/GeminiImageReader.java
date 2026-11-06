package kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini;

import java.net.http.HttpClient;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ImageReader;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gemini generateContent에 그림(inlineData)을 실어 JSON 답을 받는다. 채팅 어댑터와 같은 키·모델·시간 제한.
 * responseMimeType=application/json으로 모델이 JSON만 내게 한다 (확인 필요: 모델에 따라 코드 펜스가 섞일 수 있어 호출자가 벗긴다).
 */
public class GeminiImageReader implements ImageReader {

    private static final Logger log = LoggerFactory.getLogger(GeminiImageReader.class);

    private final RestClient rest;
    private final GeminiProperties props;

    public GeminiImageReader(GeminiProperties props) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(props.timeout()).build());
        factory.setReadTimeout(props.timeout());
        this.rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
        this.props = props;
    }

    @Override
    public String read(String instruction, String mimeType, byte[] image) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new LlmException("LLM_NOT_CONFIGURED", "AI 비서가 아직 준비되지 않았어요. 시간표를 직접 입력해 주세요.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", instruction))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(
                Map.of("inlineData", Map.of("mimeType", mimeType, "data", Base64.getEncoder().encodeToString(image))),
                Map.of("text", "이 시간표를 읽어 주세요.")))));
        body.put("generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0));
        long started = System.nanoTime();
        boolean ok = false;
        try {
            Response response = rest.post()
                    .uri("/v1beta/models/{model}:generateContent", props.model())
                    .header("x-goog-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        int status = res.getStatusCode().value();
                        log.warn("llm gemini(image) 오류 응답 status={}", status);
                        if (status == 429) {
                            throw new LlmException("LLM_RATE_LIMITED", "지금 요청이 많아요. 잠시 후 다시 시도해 주세요.");
                        }
                        throw unavailable();
                    })
                    .body(Response.class);
            String text = firstText(response);
            if (text == null || text.isBlank()) {
                throw unavailable();
            }
            ok = true;
            return text;
        } catch (ResourceAccessException e) {
            throw unavailable();
        } catch (RestClientException e) {
            log.warn("llm gemini(image) 응답 해석 실패 cause={}", e.getClass().getSimpleName());
            throw unavailable();
        } finally {
            // 그림 크기만 남긴다. 내용·키는 남기지 않는다
            log.info("llm gemini(image) ok={} bytes={} {}ms", ok, image.length, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private static String firstText(Response response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            return null;
        }
        Content content = response.candidates().getFirst().content();
        if (content == null || content.parts() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Part p : content.parts()) {
            if (p.text() != null && !Boolean.TRUE.equals(p.thought())) {
                sb.append(p.text());
            }
        }
        return sb.toString();
    }

    private static LlmException unavailable() {
        return new LlmException("LLM_UNAVAILABLE", "사진을 읽지 못했어요. 잠시 후 다시 시도하거나 직접 입력해 주세요.");
    }

    record Response(List<Candidate> candidates) {
    }

    record Candidate(Content content, String finishReason) {
    }

    record Content(String role, List<Part> parts) {
    }

    record Part(String text, Boolean thought) {
    }
}
