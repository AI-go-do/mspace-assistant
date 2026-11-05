package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AccessCodeGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentLoop;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatJobProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatJobs;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConfirmationGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.MemberKey;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ScheduleGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ToolGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면이 부르는 API.
 * <ul>
 *   <li>POST /api/agent/chat: 학생 말을 접수하고 바로 202 + jobId. 에이전트 반복은 뒤에서 돈다</li>
 *   <li>GET /api/agent/chat/{jobId}: 결과 조회(폴링). PENDING이면 pollAfterMs 뒤 다시 부른다. DONE이면 답 + 확인 카드</li>
 *   <li>DELETE /api/agent/chat/history: 서버가 기억한 앞선 대화를 지운다 ("새 대화")</li>
 *   <li>POST /api/agent/confirmations/{token}: 학생이 [확인]을 누름 → mcp-server 확인 API로 그대로 넘김 (모델 없음)</li>
 *   <li>GET /api/agent/reservations/{id}/access-code: 입실 번호 카드 → mcp-server로 그대로 넘김 (모델 없음, 대표자만)</li>
 *   <li>GET·PUT·DELETE /api/agent/schedule: 내 시간표 화면 → mcp-server /api/schedule/me로 그대로 넘김 (모델 없음)</li>
 * </ul>
 */
@RestController
class ChatController {

    /** AGENT_BUSY(503) 뒤 다시 보낼 때까지 기다릴 초. 대기열이 한 바퀴 도는 시간보다 짧게 둔다 */
    static final int RETRY_AFTER_SECONDS = 5;

    private final AgentLoop agent;
    private final ConfirmationGateway confirmations;
    private final AccessCodeGateway accessCodes;
    private final ScheduleGateway schedules;
    private final AgentProperties props;
    private final ChatJobs jobs;
    private final ChatJobProperties jobProps;
    private final ConversationMemory memory;

    ChatController(AgentLoop agent, ConfirmationGateway confirmations, AccessCodeGateway accessCodes,
                   ScheduleGateway schedules, AgentProperties props, ChatJobs jobs, ChatJobProperties jobProps,
                   ConversationMemory memory) {
        this.agent = agent;
        this.confirmations = confirmations;
        this.accessCodes = accessCodes;
        this.schedules = schedules;
        this.props = props;
        this.jobs = jobs;
        this.jobProps = jobProps;
        this.memory = memory;
    }

    @PostMapping("/api/agent/chat")
    ResponseEntity<?> chat(@RequestHeader HttpHeaders headers, @RequestBody ChatRequest request) {
        UserHeaders user = user(headers);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요해요.");
        }
        if (request == null || request.message() == null || request.message().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "말을 입력해 주세요.");
        }
        if (request.message().length() > props.maxMessageChars()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "말이 너무 길어요. %d자 안으로 줄여 주세요.".formatted(props.maxMessageChars()));
        }
        if (request.history() != null && request.history().stream().anyMatch(t -> t == null || t.text() == null)) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "대화 기록이 올바르지 않아요.");
        }
        if (request.history() != null && request.history().stream()
                .anyMatch(t -> t.text().length() > props.maxMessageChars())) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "대화 기록이 올바르지 않아요.");
        }
        // 모델 무료 할당량을 지키려고 최근 대화만 넘긴다
        List<Turn> recent = request.history() == null ? List.of() : request.history();
        if (recent.size() > props.maxHistoryTurns()) {
            recent = recent.subList(recent.size() - props.maxHistoryTurns(), recent.size());
        }
        List<LlmMessage> history = recent.stream()
                .<LlmMessage>map(t -> "assistant".equals(t.role()) ? new LlmMessage.Assistant(t.text(), List.of())
                        : new LlmMessage.User(t.text()))
                .toList();
        String message = request.message();
        return switch (jobs.submit(user, () -> agent.run(user, history, message))) {
            case ChatJobs.Submitted.Accepted a -> ResponseEntity.accepted()
                    .location(URI.create("/api/agent/chat/" + a.jobId()))
                    .cacheControl(CacheControl.noStore())
                    .body(new Accepted(a.jobId(), ChatJobs.Status.PENDING, jobProps.pollAfter().toMillis()));
            case ChatJobs.Submitted.Rejected r when "CHAT_IN_PROGRESS".equals(r.code()) ->
                    error(HttpStatus.CONFLICT, r.code(), r.message());
            // 붐빌 때는 화면이 언제 다시 보낼지 알 수 있게 Retry-After(초)
            case ChatJobs.Submitted.Rejected r -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS))
                    .body(error(HttpStatus.SERVICE_UNAVAILABLE, r.code(), r.message()).getBody());
        };
    }

    /** 결과에는 확인 토큰이 들어 있어 접수한 학생만 보고(남의 jobId는 404), 캐시에 남지 않게 no-store */
    @GetMapping("/api/agent/chat/{jobId}")
    ResponseEntity<?> chatResult(@PathVariable String jobId, @RequestHeader HttpHeaders headers) {
        UserHeaders user = user(headers);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요해요.");
        }
        return jobs.find(jobId, user)
                .<ResponseEntity<?>>map(view -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(view.status() == ChatJobs.Status.PENDING
                                ? new Pending(view.jobId(), view.status(), jobProps.pollAfter().toMillis())
                                : view))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "CHAT_JOB_NOT_FOUND",
                        "대화 결과를 찾지 못했어요. 다시 보내 주세요."));
    }

    /**
     * "새 대화": 서버가 기억한 앞선 대화를 지운다. 화면도 자기 history를 비운다.
     * 이 학생의 채팅이 아직 돌고 있으면 409: 끝날 때 옛 대화를 다시 저장해 지운 것이 되살아나기 때문이다
     */
    @DeleteMapping("/api/agent/chat/history")
    ResponseEntity<?> clearHistory(@RequestHeader HttpHeaders headers) {
        UserHeaders user = user(headers);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요해요.");
        }
        if (jobs.hasRunning(user)) {
            return error(HttpStatus.CONFLICT, "CHAT_IN_PROGRESS", "앞의 질문에 아직 답하는 중이에요. 답이 온 뒤에 지워 주세요.");
        }
        memory.clear(MemberKey.of(user));
        return ResponseEntity.noContent().build();
    }

    /** 확인 결과(입실 번호 포함)는 학생 화면으로만 간다. 모델에는 넘기지 않는다 */
    @PostMapping("/api/agent/confirmations/{token}")
    ResponseEntity<String> confirm(@PathVariable String token, @RequestHeader HttpHeaders headers,
                                   @RequestBody(required = false) String body) {
        ConfirmationGateway.Relayed relayed = confirmations.confirm(token, user(headers), body);
        // 예약 생성 결과에는 입실 번호가 들어 있어 브라우저·프록시가 저장하지 않게 한다
        return ResponseEntity.status(relayed.status()).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(relayed.json());
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

    /** 내 시간표 보기. 없으면 mcp-server의 404 NO_SCHEDULE 그대로 */
    @GetMapping("/api/agent/schedule")
    ResponseEntity<?> schedule(@RequestHeader HttpHeaders headers) {
        return relaySchedule("GET", headers, null);
    }

    /** 내 시간표 통째로 바꾸기. 본문 {classes:[{dayOfWeek, startTime, endTime, name}]} 검사는 mcp-server가 한다 */
    @PutMapping("/api/agent/schedule")
    ResponseEntity<?> replaceSchedule(@RequestHeader HttpHeaders headers, @RequestBody(required = false) String body) {
        return relaySchedule("PUT", headers, body == null ? "{}" : body);
    }

    @DeleteMapping("/api/agent/schedule")
    ResponseEntity<?> deleteSchedule(@RequestHeader HttpHeaders headers) {
        return relaySchedule("DELETE", headers, null);
    }

    private ResponseEntity<?> relaySchedule(String method, HttpHeaders headers, String body) {
        UserHeaders user = user(headers);
        if (user.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다");
        }
        ConfirmationGateway.Relayed relayed = schedules.relay(method, user, body);
        if (relayed.status() == HttpStatus.NO_CONTENT.value()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(relayed.status()).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(relayed.json());
    }

    @ExceptionHandler(LlmException.class)
    ResponseEntity<?> llmFailed(LlmException e) {
        HttpStatus status = "LLM_RATE_LIMITED".equals(e.code()) ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        return error(status, e.code(), e.getMessage());
    }

    @ExceptionHandler(ConversationMemory.Unavailable.class)
    ResponseEntity<?> memoryFailed(ConversationMemory.Unavailable e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "MEMORY_UNAVAILABLE", e.getMessage());
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

    /** history: 앞선 대화 [{role: "user"|"assistant", text}]. 서버가 기억한 대화가 없을 때만 쓴다 (설계 4장) */
    record ChatRequest(String message, List<Turn> history) {
    }

    record Turn(String role, String text) {
    }

    /** pollAfterMs: 화면이 다음 조회까지 기다릴 시간 */
    record Accepted(String jobId, ChatJobs.Status status, long pollAfterMs) {
    }

    record Pending(String jobId, ChatJobs.Status status, long pollAfterMs) {
    }
}
