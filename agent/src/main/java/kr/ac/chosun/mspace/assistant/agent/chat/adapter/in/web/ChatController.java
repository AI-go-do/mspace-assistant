package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.INVALID_REQUEST;
import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.user;
import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.error;
import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.unauthorized;

import java.net.URI;
import java.util.List;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentLoop;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatJobProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatJobs;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.MemberKey;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 화면이 부르는 API.
 * <ul>
 *   <li>POST /api/agent/chat: 학생 말을 접수하고 바로 202 + jobId. 에이전트 반복은 뒤에서 돈다</li>
 *   <li>GET /api/agent/chat/{jobId}: 결과 조회(폴링). PENDING이면 pollAfterMs 뒤 다시 부른다. DONE이면 답 + 확인 카드</li>
 *   <li>DELETE /api/agent/chat/history: 서버가 기억한 앞선 대화를 지운다 ("새 대화")</li>
 *   <li>확인 카드·입실 번호는 {@link ConfirmationController}, 시간표는 {@link ScheduleController},
 *       브리핑·성향은 {@link AssistantSettingsController}. 오류 모양은 {@link AgentErrors}</li>
 * </ul>
 */
@RestController
class ChatController {

    /** AGENT_BUSY(503) 뒤 다시 보낼 때까지 기다릴 초. 대기열이 한 바퀴 도는 시간보다 짧게 둔다 */
    static final int RETRY_AFTER_SECONDS = 5;

    private final AgentLoop agent;
    private final AgentProperties props;
    private final ChatJobs jobs;
    private final ChatJobProperties jobProps;
    private final ConversationMemory memory;

    ChatController(AgentLoop agent, AgentProperties props, ChatJobs jobs, ChatJobProperties jobProps, ConversationMemory memory) {
        this.agent = agent;
        this.props = props;
        this.jobs = jobs;
        this.jobProps = jobProps;
        this.memory = memory;
    }

    @PostMapping("/api/agent/chat")
    ResponseEntity<Object> chat(@RequestHeader HttpHeaders headers, @RequestBody ChatRequest request) {
        UserHeaders user = user(props, headers);
        if (user.isEmpty()) {
            return unauthorized();
        }
        if (request == null || request.message() == null || request.message().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "말을 입력해 주세요.");
        }
        if (request.message().length() > props.maxMessageChars()) {
            return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST,
                    "말이 너무 길어요. %d자 안으로 줄여 주세요.".formatted(props.maxMessageChars()));
        }
        if (request.history() != null && request.history().stream().anyMatch(t -> t == null || t.text() == null)) {
            return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "대화 기록이 올바르지 않아요.");
        }
        if (request.history() != null && request.history().stream()
                .anyMatch(t -> t.text().length() > props.maxMessageChars())) {
            return error(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "대화 기록이 올바르지 않아요.");
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
    ResponseEntity<Object> chatResult(@PathVariable String jobId, @RequestHeader HttpHeaders headers) {
        UserHeaders user = user(props, headers);
        if (user.isEmpty()) {
            return unauthorized();
        }
        return jobs.find(jobId, user)
                .<ResponseEntity<Object>>map(view -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
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
    ResponseEntity<Object> clearHistory(@RequestHeader HttpHeaders headers) {
        UserHeaders user = user(props, headers);
        if (user.isEmpty()) {
            return unauthorized();
        }
        if (jobs.hasRunning(user)) {
            return error(HttpStatus.CONFLICT, "CHAT_IN_PROGRESS", "앞의 질문에 아직 답하는 중이에요. 답이 온 뒤에 지워 주세요.");
        }
        memory.clear(MemberKey.of(user));
        return ResponseEntity.noContent().build();
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
