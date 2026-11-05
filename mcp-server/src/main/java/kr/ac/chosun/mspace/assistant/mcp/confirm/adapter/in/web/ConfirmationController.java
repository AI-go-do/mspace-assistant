package kr.ac.chosun.mspace.assistant.mcp.confirm.adapter.in.web;

import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.adapter.in.web.ForwardedCredentials;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ExecuteConfirmation;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ExecuteConfirmation.Executed;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 확인 실행 API. MCP 도구가 아니라 일반 REST라서 모델은 부를 수 없다.
 * 학생이 화면에서 [확인]을 누르면 에이전트가 학생 본인의 헤더를 실어 부른다.
 */
@RestController
class ConfirmationController {

    private final ExecuteConfirmation execute;
    private final ForwardedCredentials credentials;

    ConfirmationController(ExecuteConfirmation execute, ForwardedCredentials credentials) {
        this.execute = execute;
        this.credentials = credentials;
    }

    /** 예: POST /api/confirmations/c_9f2… {"participantStudentIds":["20230002","20230003"]} */
    @PostMapping("/api/confirmations/{token}")
    Executed confirm(@PathVariable String token, @RequestHeader HttpHeaders headers,
                     @RequestBody(required = false) ConfirmRequest request) {
        return execute.execute(token, credentials.require(headers),
                request == null ? List.of() : request.participantStudentIds());
    }

    record ConfirmRequest(List<String> participantStudentIds) {
    }
}
