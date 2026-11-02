package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;

/** mcp-server의 도구 목록·호출. 어댑터는 MCP Streamable HTTP 클라이언트 */
public interface ToolGateway {

    /**
     * 학생 헤더로 MCP 세션을 열고, 도구 목록을 받아 둔다. 대화 한 번 동안 같은 세션을 쓴다.
     */
    Session open(UserHeaders headers);

    interface Session extends AutoCloseable {

        List<ToolSpec> tools();

        /** @return 도구 결과 본문(JSON 문자열)과 오류 여부 */
        Outcome call(String name, Map<String, Object> arguments);

        @Override
        void close();
    }

    record Outcome(String json, boolean isError) {
    }

    /** mcp-server에 붙지 못함. message는 학생에게 보여 줄 문장 */
    class Unavailable extends RuntimeException {
        public Unavailable() {
            super("예약 도구에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
    }
}
