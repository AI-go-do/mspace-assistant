package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult.Confirmation;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ToolGateway.Outcome;
import tools.jackson.databind.json.JsonMapper;

/**
 * 쓰기 도구 결과에서 확인 토큰을 빼서 화면용 확인 카드로 만들고, 모델에는 토큰 없는 결과를 준다.
 * 모델은 확인 API를 부를 수 없지만, 토큰이 답 문장에 섞여 나가는 일도 막는다.
 * mcp-server 결과 모양(confirmToken·summary 등)을 아는 건 어댑터라 여기에 둔다.
 */
public final class ProposalParser {

    static final String CARD_NOTE = "학생 화면에 [확인] 카드로 보여 줬음. 학생이 눌러야 실행됨";

    private ProposalParser() {
    }

    @SuppressWarnings("unchecked")
    public static Outcome parse(String tool, String text, boolean isError, JsonMapper json) {
        if (isError) {
            return new Outcome(text, true);
        }
        Map<String, Object> body;
        try {
            body = json.readValue(text, LinkedHashMap.class);
        } catch (RuntimeException e) {
            return new Outcome(text, false);
        }
        Object token = body.remove("confirmToken");
        if (token == null) {
            return new Outcome(text, false);
        }
        Object notes = body.get("notes");
        Object needs = body.get("needsParticipants");
        var card = new Confirmation(tool, token.toString(), String.valueOf(body.get("summary")),
                notes instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(),
                needs instanceof Number n ? n.intValue() : 0, String.valueOf(body.get("expiresAt")));
        body.put("confirmation", CARD_NOTE);
        return new Outcome(json.writeValueAsString(body), false, Optional.of(card));
    }
}
