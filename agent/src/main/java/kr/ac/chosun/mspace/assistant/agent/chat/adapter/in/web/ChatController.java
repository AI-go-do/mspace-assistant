package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AccessCodeGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentLoop;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConfirmationGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ToolGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면이 부르는 API.
 * <ul>
 *   <li>POST /api/agent/chat: 학생 말 → 에이전트 반복 → 답 + 확인 카드</li>
 *   <li>POST /api/agent/confirmations/{token}: 학생이 [확인]을 누름 → mcp-server 확인 API로 그대로 넘김 (모델 없음)</li>
 *   <li>GET /api/agent/reservations/{id}/access-code: 입실 번호 카드 → mcp-server로 그대로 넘김 (모델 없음, 대표자만)</li>
 * </ul>
 */
@RestController
class ChatController {

    private final AgentLoop agent;
    private final ConfirmationGateway confirmations;
    private final AccessCodeGateway accessCodes;
    private final AgentProperties props;

    ChatController(AgentLoop agent, ConfirmationGateway confirmations, AccessCodeGateway accessCodes,
                   AgentProperties props) {
        this.agent = agent;
        this.confirmations = confirmations;
        this.accessCodes = accessCodes;
        this.props = props;
    }

    @PostMapping("/api/agent/chat")
    ResponseEntity<?> chat(@RequestHeader HttpHeaders headers, @RequestBody ChatRequest request) {
        UserHeaders user = user(headers);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다");
        }
        if (request == null || request.message() == null || request.message().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "말을 입력해 주세요.");
        }
        if (request.history() != null && request.history().stream().anyMatch(t -> t == null || t.text() == null)) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "대화 기록이 올바르지 않아요.");
        }
        List<LlmMessage> history = request.history() == null ? List.of() : request.history().stream()
                .<LlmMessage>map(t -> "assistant".equals(t.role()) ? new LlmMessage.Assistant(t.text(), List.of())
                        : new LlmMessage.User(t.text()))
                .toList();
        ChatResult result = agent.run(user, history, request.message());
        return ResponseEntity.ok(result);
    }

    /** 확인 결과(입실 번호 포함)는 학생 화면으로만 간다. 모델에는 넘기지 않는다 */
    @PostMapping("/api/agent/confirmations/{token}")
    ResponseEntity<String> confirm(@PathVariable String token, @RequestHeader HttpHeaders headers,
                                   @RequestBody(required = false) String body) {
        ConfirmationGateway.Relayed relayed = confirmations.confirm(token, user(headers), body);
        return ResponseEntity.status(relayed.status()).contentType(MediaType.APPLICATION_JSON).body(relayed.json());
    }

    /** 입실 번호는 학생 화면으로만 간다. 캐시에 남지 않게 no-store */
    @GetMapping("/api/agent/reservations/{reservationId}/access-code")
    ResponseEntity<?> accessCode(@PathVariable long reservationId, @RequestHeader HttpHeaders headers) {
        UserHeaders user = user(headers);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다");
        }
        ConfirmationGateway.Relayed relayed = accessCodes.accessCode(reservationId, user);
        return ResponseEntity.status(relayed.status()).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(relayed.json());
    }

    @ExceptionHandler(LlmException.class)
    ResponseEntity<?> llmFailed(LlmException e) {
        HttpStatus status = "LLM_RATE_LIMITED".equals(e.code()) ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        return error(status, e.code(), e.getMessage());
    }

    @ExceptionHandler(ToolGateway.Unavailable.class)
    ResponseEntity<?> toolsFailed(ToolGateway.Unavailable e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "TOOLS_UNAVAILABLE", e.getMessage());
    }

    private UserHeaders user(HttpHeaders headers) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : props.forwardHeaders()) {
            String value = headers.getFirst(name);
            if (value != null && !value.isBlank()) {
                values.put(name, value);
            }
        }
        return new UserHeaders(values);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    /** history: 앞선 대화 [{role: "user"|"assistant", text}]. 화면이 들고 있다가 보낸다 */
    record ChatRequest(String message, List<Turn> history) {
    }

    record Turn(String role, String text) {
    }
}
