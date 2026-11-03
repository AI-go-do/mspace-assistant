package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult.AccessCodeCard;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult.Confirmation;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;

/** mcp-server의 도구 목록·호출. 어댑터는 MCP Streamable HTTP 클라이언트 */
public interface ToolGateway {

    /**
     * 학생 헤더로 MCP 세션을 열고, 도구 목록을 받아 둔다. 대화 한 번 동안 같은 세션을 쓴다.
     */
    Session open(UserHeaders headers);

    interface Session extends AutoCloseable {

        List<ToolSpec> tools();

        /** @return 모델에게 줄 결과(확인 토큰은 빠짐), 오류 여부, 쓰기 도구면 확인 카드 */
        Outcome call(String name, Map<String, Object> arguments);

        @Override
        void close();
    }

    /**
     * @param json         모델에게 줄 결과 JSON. 확인 토큰은 들어 있지 않다
     * @param confirmation 쓰기 도구가 확인안을 냈으면 화면용 카드 (토큰 포함)
     * @param accessCodeCard show_access_code가 대표자라고 답했으면 화면용 입실 번호 카드 (번호 없음)
     */
    record Outcome(String json, boolean isError, Optional<Confirmation> confirmation,
                   Optional<AccessCodeCard> accessCodeCard) {

        public Outcome(String json, boolean isError) {
            this(json, isError, Optional.empty(), Optional.empty());
        }

        public Outcome(String json, boolean isError, Optional<Confirmation> confirmation) {
            this(json, isError, confirmation, Optional.empty());
        }
    }

    /** mcp-server에 붙지 못함. message는 학생에게 보여 줄 문장 */
    class Unavailable extends RuntimeException {
        public Unavailable() {
            super("예약 도구에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
    }
}
