package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * multipart 크기 초과는 컨트롤러 메서드에 닿기 전(DispatcherServlet의 multipart 해석)에 터져서
 * ChatController 안의 @ExceptionHandler로는 못 잡는다. 전역 advice로 API 계약(400 INVALID_IMAGE)에 맞춘다.
 */
@RestControllerAdvice
class UploadErrorAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "INVALID_IMAGE", "message", "사진은 5MB까지 올릴 수 있어요."));
    }
}
