package kr.ac.chosun.mspace.assistant.mcp.common.adapter.in.web;

import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST 오류를 한 곳에서 팀 공통 모양 {code, message}로 바꾼다 (팀 컨벤션 3장).
 * MCP 도구 오류는 여기가 아니라 도구 결과(isError)로 돌아간다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(CoreApiException.class)
    ResponseEntity<ErrorBody> handle(CoreApiException e) {
        return ResponseEntity.status(e.status()).body(new ErrorBody(e.code(), e.getMessage()));
    }

    record ErrorBody(String code, String message) {
    }
}
