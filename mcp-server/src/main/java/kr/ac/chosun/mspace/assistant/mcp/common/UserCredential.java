package kr.ac.chosun.mspace.assistant.mcp.common;

import java.util.Map;
import java.util.Optional;

/**
 * 사용자 본인의 인증 헤더. 에이전트가 MCP 요청에 실어 보낸 값을 예약 코어에 그대로 넘긴다.
 * 이 서버는 관리자 토큰·공용 토큰을 갖지 않는다 (CLAUDE.md 4장: 권한 검사는 예약 코어가 한다).
 *
 * @param headers 넘길 헤더 이름 → 값 (예: Authorization → "Bearer ...")
 */
public record UserCredential(Map<String, String> headers) {

    /** MCP 요청 문맥(McpTransportContext)에 넣을 때 쓰는 열쇠 */
    public static final String CONTEXT_KEY = "mspace.userCredential";

    public UserCredential {
        headers = Map.copyOf(headers);
    }

    public static Optional<UserCredential> from(Object contextValue) {
        return contextValue instanceof UserCredential c && !c.headers().isEmpty()
                ? Optional.of(c) : Optional.empty();
    }

    /** 로그에 찍어도 되는 모양. 값은 절대 보이지 않게 한다 */
    @Override
    public String toString() {
        return "UserCredential" + headers.keySet();
    }
}
