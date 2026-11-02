package kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.gemini;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmReply;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolCall;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini generateContent REST 어댑터 (v1beta). SDK 없이 RestClient로 부른다(새 라이브러리 없이).
 * 도구 스키마는 MCP의 JSON Schema를 그대로 쓰려고 parametersJsonSchema로 보낸다 (확인 필요: 모델·API 버전에 따라 지원이 다를 수 있음).
 * 로그에는 걸린 시간·성공 여부만 남긴다. 대화 내용·키는 남기지 않는다.
 */
public class GeminiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);

    private final RestClient rest;
    private final JsonMapper json;
    private final GeminiProperties props;

    public GeminiLlmClient(GeminiProperties props, JsonMapper json) {
        // HTTP/1.1 고정: JDK 기본(HTTP/2)은 http:// 주소에서 h2c 업그레이드를 시도하는데,
        // 본문이 있는 POST에서 일부 서버(WireMock 등)가 스트림을 끊는다(RST_STREAM, TS-04)
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(props.timeout()).build());
        factory.setReadTimeout(props.timeout());
        this.rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
        this.json = json;
        this.props = props;
    }

    @Override
    public LlmReply chat(String system, List<LlmMessage> conversation, List<ToolSpec> tools) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new LlmException("LLM_NOT_CONFIGURED", "AI 비서가 아직 준비되지 않았어요. 예약 화면을 이용해 주세요.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
        body.put("contents", contents(conversation));
        if (!tools.isEmpty()) {
            body.put("tools", List.of(Map.of("functionDeclarations", tools.stream().map(GeminiLlmClient::declaration).toList())));
        }
        long started = System.nanoTime();
        boolean ok = false;
        try {
            GenerateResponse response = rest.post()
                    .uri("/v1beta/models/{model}:generateContent", props.model())
                    .header("x-goog-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw res.getStatusCode().value() == 429
                                ? new LlmException("LLM_RATE_LIMITED",
                                        "지금 질문이 많아요. 잠시 후 다시 시도하거나 예약 화면을 이용해 주세요.")
                                : unavailable();
                    })
                    .body(GenerateResponse.class);
            LlmReply reply = toReply(response);
            ok = true;
            return reply;
        } catch (ResourceAccessException e) {
            throw unavailable();
        } catch (RestClientException e) {
            // 응답은 왔는데 모양이 다름(본문 해석 실패 등). 원인은 로그에만, 사용자에게는 같은 안내
            log.warn("llm gemini 응답 해석 실패 cause={}", e.getClass().getSimpleName());
            throw unavailable();
        } finally {
            log.info("llm gemini ok={} {}ms", ok, (System.nanoTime() - started) / 1_000_000);
        }
    }

    /**
     * 한 번에 여러 도구를 부르면(병렬 호출) 결과도 한 user 차례에 functionResponse 여러 개로 묶어 보낸다.
     * 결과마다 user 차례를 따로 만들면 호출 수와 응답 수가 안 맞아 Gemini가 거절할 수 있다 (확인 필요).
     */
    private List<Map<String, Object>> contents(List<LlmMessage> conversation) {
        List<Map<String, Object>> contents = new ArrayList<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (LlmMessage m : conversation) {
            if (m instanceof LlmMessage.ToolResult r) {
                Map<String, Object> fr = new LinkedHashMap<>();
                if (fromGemini(r.callId())) {
                    fr.put("id", r.callId()); // Gemini가 준 호출 id가 있으면 응답에도 같은 id (병렬 호출 짝 맞추기)
                }
                fr.put("name", r.name());
                fr.put("response", response(r));
                results.add(Map.of("functionResponse", fr));
                continue;
            }
            flushResults(contents, results);
            switch (m) {
                case LlmMessage.User u -> contents.add(content("user", List.of(Map.of("text", u.text()))));
                case LlmMessage.Assistant a -> {
                    List<Map<String, Object>> parts = new ArrayList<>();
                    if (a.text() != null && !a.text().isBlank()) {
                        parts.add(Map.of("text", a.text()));
                    }
                    for (ToolCall call : a.toolCalls()) {
                        Map<String, Object> part = new LinkedHashMap<>();
                        Map<String, Object> fc = new LinkedHashMap<>();
                        if (fromGemini(call.id())) {
                            fc.put("id", call.id());
                        }
                        fc.put("name", call.name());
                        fc.put("args", call.arguments());
                        part.put("functionCall", fc);
                        if (call.signature() != null) {
                            part.put("thoughtSignature", call.signature());
                        }
                        parts.add(part);
                    }
                    contents.add(content("model", parts));
                }
                case LlmMessage.ToolResult r -> throw new IllegalStateException("위에서 처리함");
            }
        }
        flushResults(contents, results);
        return contents;
    }

    private static void flushResults(List<Map<String, Object>> contents, List<Map<String, Object>> results) {
        if (!results.isEmpty()) {
            contents.add(content("user", List.copyOf(results)));
            results.clear();
        }
    }

    /** functionResponse.response는 객체여야 한다. 도구 결과 JSON을 result(또는 error) 아래에 둔다 */
    private Map<String, Object> response(LlmMessage.ToolResult r) {
        Object parsed;
        try {
            parsed = json.readValue(r.json(), Object.class);
        } catch (RuntimeException e) {
            parsed = r.json();
        }
        // 도구 결과가 JSON null이어도 {"result":null}로 그대로 보낸다 (Map.of는 null을 못 받아 LinkedHashMap)
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(r.isError() ? "error" : "result", parsed);
        return response;
    }

    private static Map<String, Object> content(String role, List<Map<String, Object>> parts) {
        return Map.of("role", role, "parts", parts);
    }

    private static Map<String, Object> declaration(ToolSpec tool) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", tool.name());
        d.put("description", tool.description());
        d.put("parametersJsonSchema", tool.inputSchema());
        return d;
    }

    private static LlmReply toReply(GenerateResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()
                || response.candidates().getFirst() == null
                || response.candidates().getFirst().content() == null) {
            throw unavailable();
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        List<Part> parts = response.candidates().getFirst().content().parts();
        for (Part part : parts == null ? List.<Part>of() : parts) {
            if (part == null) {
                throw unavailable(); // [null] 같은 깨진 응답이 NullPointerException으로 새지 않게
            }
            if (part.functionCall() != null) {
                FunctionCall f = part.functionCall();
                String id = f.id() != null ? f.id() : LOCAL_ID + UUID.randomUUID();
                calls.add(new ToolCall(id, f.name(), f.args(), part.thoughtSignature()));
            } else if (part.text() != null && !Boolean.TRUE.equals(part.thought())) {
                text.append(part.text());
            }
        }
        return new LlmReply(text.toString(), calls);
    }

    /** Gemini가 id를 안 주면 어댑터가 만든 id. 이 id는 Gemini로 다시 보내지 않는다 */
    private static final String LOCAL_ID = "local_";

    private static boolean fromGemini(String id) {
        return id != null && !id.startsWith(LOCAL_ID);
    }

    private static LlmException unavailable() {
        return new LlmException("LLM_UNAVAILABLE", "AI 비서가 지금 응답하지 않아요. 잠시 후 다시 시도해 주세요.");
    }

    record GenerateResponse(List<Candidate> candidates) {
    }

    record Candidate(Content content, String finishReason) {
    }

    record Content(String role, List<Part> parts) {
    }

    record Part(String text, Boolean thought, FunctionCall functionCall, String thoughtSignature) {
    }

    record FunctionCall(String id, String name, Map<String, Object> args) {
    }
}
