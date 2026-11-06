package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ReadTimetableImage;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ToolGateway;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 비서 API 컨트롤러(채팅·확인·시간표·브리핑·성향)의 오류를 {code, message}로 */
@RestControllerAdvice(assignableTypes = {ChatController.class, ConfirmationController.class, ScheduleController.class,
        AssistantSettingsController.class})
class AgentErrors {

    @ExceptionHandler(ReadTimetableImage.InvalidImage.class)
    ResponseEntity<Object> badImage(ReadTimetableImage.InvalidImage e) {
        return WebRequests.error(HttpStatus.BAD_REQUEST, WebRequests.INVALID_IMAGE, e.getMessage());
    }

    @ExceptionHandler(LlmException.class)
    ResponseEntity<Object> llmFailed(LlmException e) {
        HttpStatus status = "LLM_RATE_LIMITED".equals(e.code()) ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        return WebRequests.error(status, e.code(), e.getMessage());
    }

    @ExceptionHandler(ConversationMemory.Unavailable.class)
    ResponseEntity<Object> memoryFailed(ConversationMemory.Unavailable e) {
        return WebRequests.error(HttpStatus.SERVICE_UNAVAILABLE, "MEMORY_UNAVAILABLE", e.getMessage());
    }

    @ExceptionHandler(ToolGateway.Unavailable.class)
    ResponseEntity<Object> toolsFailed(ToolGateway.Unavailable e) {
        return WebRequests.error(HttpStatus.SERVICE_UNAVAILABLE, "TOOLS_UNAVAILABLE", e.getMessage());
    }
}
