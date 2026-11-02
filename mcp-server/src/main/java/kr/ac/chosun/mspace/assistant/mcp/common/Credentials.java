package kr.ac.chosun.mspace.assistant.mcp.common;

import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

/** 도구 요청 문맥에서 사용자 자격 정보를 꺼낸다. 없으면 코어를 부르지 않고 바로 오류 */
public final class Credentials {

    private Credentials() {
    }

    public static UserCredential require(McpSyncRequestContext context) {
        return UserCredential.from(context.transportContext().get(UserCredential.CONTEXT_KEY))
                .orElseThrow(() -> new CoreApiException("UNAUTHORIZED", "로그인이 필요합니다", 401));
    }
}
