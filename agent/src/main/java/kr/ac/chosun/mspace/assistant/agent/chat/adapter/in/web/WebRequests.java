package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 비서 API 컨트롤러가 같이 쓰는 것: 학생 헤더 고르기, 오류 본문 {code, message} */
final class WebRequests {

    static final String UNAUTHORIZED = "UNAUTHORIZED";
    static final String LOGIN_REQUIRED = "로그인이 필요해요.";
    static final String INVALID_REQUEST = "INVALID_REQUEST";
    static final String INVALID_IMAGE = "INVALID_IMAGE";

    private WebRequests() {
    }

    /** 설정한 헤더(Authorization 등)만 골라 mcp-server로 넘긴다. 비어 있으면 로그인 안 한 것 */
    static UserHeaders user(AgentProperties props, HttpHeaders headers) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : props.forwardHeaders()) {
            String value = headers.getFirst(name);
            if (value != null && !value.isBlank()) {
                values.put(name, value);
            }
        }
        return new UserHeaders(values);
    }

    static ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    static ResponseEntity<Object> loginRequired() {
        return error(HttpStatus.UNAUTHORIZED, UNAUTHORIZED, LOGIN_REQUIRED);
    }
}
