package kr.ac.chosun.mspace.assistant.mcp.common.adapter.in.web;

import java.util.LinkedHashMap;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreProperties;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * REST 요청(모델이 부를 수 없는 경로)에서 학생 본인의 인증 헤더만 골라 낸다.
 * 넘길 헤더 목록은 MCP 요청과 같은 mspace.core.forward-headers를 쓴다. 하나도 없으면 401.
 */
@Component
public class ForwardedCredentials {

    private final CoreProperties core;

    ForwardedCredentials(CoreProperties core) {
        this.core = core;
    }

    public UserCredential require(HttpHeaders headers) {
        Map<String, String> forwarded = new LinkedHashMap<>();
        for (String name : core.forwardHeaders()) {
            String value = headers.getFirst(name);
            if (value != null && !value.isBlank()) {
                forwarded.put(name, value);
            }
        }
        return UserCredential.from(new UserCredential(forwarded))
                .orElseThrow(() -> new CoreApiException("UNAUTHORIZED", "로그인이 필요합니다", 401));
    }
}
