package kr.ac.chosun.mspace.assistant.agent.llm.adapter.out.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

/**
 * Ollama /api/chat 어댑터 (stream=false). 데이터가 학교 밖으로 나가지 않는다.
 * 요청 모양은 Ollama 공식 API 문서 기준 (확인 필요: 버전마다 tool 메시지 필드가 다를 수 있음).
 * 로그에는 걸린 시간·성공 여부만 남긴다.
 */
public class OllamaLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmClient.class);

    private final RestClient rest;
    private final OllamaProperties props;

    public OllamaLlmClient(OllamaProperties props) {
        // Ollama는 http:// 라서 HTTP/1.1 고정 (TS-04와 같은 이유)
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(props.timeout());
        this.rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
        this.props = props;
    }

    @Override
    public LlmReply chat(String system, List<LlmMessage> conversation, List<ToolSpec> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("stream", false);
        body.put("messages", messages(system, conversation));
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(OllamaLlmClient::tool).toList());
        }
        long started = System.nanoTime();
        boolean ok = false;
        try {
            ChatResponse response = rest.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw unavailable();
                    })
                    .body(ChatResponse.class);
            LlmReply reply = toReply(response);
            ok = true;
            return reply;
        } catch (ResourceAccessException e) {
            throw unavailable();
        } catch (RestClientException e) {
            // 200인데 본문이 JSON이 아님 등. Gemini 어댑터와 같은 계약(LlmException)으로 맞춘다
            log.warn("llm ollama 응답 해석 실패 cause={}", e.getClass().getSimpleName());
            throw unavailable();
        } finally {
            log.info("llm ollama ok={} {}ms", ok, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private static final String CONTENT = "content";
    private static final String FUNCTION = "function";

    private static List<Map<String, Object>> messages(String system, List<LlmMessage> conversation) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", CONTENT, orEmpty(system)));
        for (LlmMessage m : conversation) {
            messages.add(switch (m) {
                case LlmMessage.User(String text) -> Map.of("role", "user", CONTENT, orEmpty(text));
                case LlmMessage.Assistant(String text, List<ToolCall> toolCalls) -> assistant(text, toolCalls);
                case LlmMessage.ToolResult(var callId, String name, String json, boolean isError) -> Map.of("role", "tool",
                        "tool_name", orEmpty(name), CONTENT, isError ? "{\"error\":" + json + "}" : orEmpty(json));
            });
        }
        return messages;
    }

    private static Map<String, Object> assistant(String text, List<ToolCall> toolCalls) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put(CONTENT, orEmpty(text));
        if (!toolCalls.isEmpty()) {
            msg.put("tool_calls", toolCalls.stream()
                    .map(c -> Map.of(FUNCTION, Map.of("name", orEmpty(c.name()),
                            "arguments", c.arguments() == null ? Map.of() : c.arguments())))
                    .toList());
        }
        return msg;
    }

    private static Map<String, Object> tool(ToolSpec spec) {
        // Map.of는 null을 받지 않는다. 설명·스키마가 빠진 도구 하나 때문에 대화 전체가 500이 되지 않게 빈 값으로 둔다
        return Map.of("type", FUNCTION, FUNCTION, Map.of(
                "name", orEmpty(spec.name()), "description", orEmpty(spec.description()),
                "parameters", spec.inputSchema() == null ? Map.of("type", "object") : spec.inputSchema()));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static LlmReply toReply(ChatResponse response) {
        if (response == null || response.message() == null) {
            throw unavailable();
        }
        List<ToolCall> calls = toolCalls(response.message());
        String text = orEmpty(response.message().content());
        if (calls.isEmpty() && text.isBlank()) {
            // 빈 말풍선을 성공으로 돌려주지 않는다 (Gemini 어댑터와 같은 계약)
            throw unavailable();
        }
        return new LlmReply(text, calls, usage(response));
    }

    private static List<ToolCall> toolCalls(Message message) {
        List<ToolCall> calls = new ArrayList<>();
        if (message.toolCalls() == null) {
            return calls;
        }
        for (OllamaToolCall c : message.toolCalls()) {
            if (c != null && c.function() != null && c.function().name() != null) {
                Map<String, Object> args = c.function().arguments() == null ? Map.of() : c.function().arguments();
                // Ollama는 호출 id를 주지 않아서 만든다. 결과는 tool_name으로 짝을 맞춘다
                calls.add(new ToolCall("call_" + UUID.randomUUID(), c.function().name(), args, null));
            }
        }
        return calls;
    }

    private static LlmReply.Usage usage(ChatResponse response) {
        return response.promptEvalCount() == null || response.evalCount() == null
                ? LlmReply.Usage.UNKNOWN : new LlmReply.Usage(response.promptEvalCount(), response.evalCount());
    }

    private static LlmException unavailable() {
        return new LlmException("LLM_UNAVAILABLE", "AI 비서가 지금 응답하지 않아요. 잠시 후 다시 시도해 주세요.");
    }

    record ChatResponse(Message message, Boolean done,
            @JsonProperty("prompt_eval_count") Integer promptEvalCount, @JsonProperty("eval_count") Integer evalCount) {
    }

    record Message(String role, String content,
            @JsonProperty("tool_calls") List<OllamaToolCall> toolCalls) {
    }

    record OllamaToolCall(Function function) {
    }

    record Function(String name, Map<String, Object> arguments) {
    }
}
