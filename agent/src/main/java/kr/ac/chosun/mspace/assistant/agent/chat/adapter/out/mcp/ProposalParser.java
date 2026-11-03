package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult.AccessCodeCard;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult.Confirmation;
import kr.ac.chosun.mspace.assistant.agent.chat.application.PrivacyMask;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ToolGateway.Outcome;
import tools.jackson.databind.json.JsonMapper;

/**
 * 도구 결과에서 화면 카드를 뽑는다.
 * <p>
 * 쓰기 도구 결과에서 확인 토큰을 빼서 화면용 확인 카드로 만들고, 모델에는 토큰 없는 결과를 준다.
 * 모델은 확인 API를 부를 수 없지만, 토큰이 답 문장에 섞여 나가는 일도 막는다.
 * show_access_code가 cardShown=true를 주면 입실 번호 카드(번호 없음)를 만든다. 번호는 화면이 따로 받는다.
 * mcp-server 결과 모양(confirmToken·summary·cardShown 등)을 아는 건 어댑터라 여기에 둔다.
 * <p>
 * 외부 AI로 가는 결과는 여기서 가린다: JSON이면 accessCode 키를 지우고 <b>문자열 값에만</b> {@link PrivacyMask}를 건다.
 * JSON 전체 글자에 정규식을 걸면 6자리 숫자 값(예: reservationId 100000)이 "[입실 번호]"로 바뀌어 JSON이 깨진다.
 * JSON이 아닌 결과(오류 문장 등)는 자유 텍스트라 통째로 가린다.
 */
public final class ProposalParser {

    static final String ACCESS_CODE_TOOL = "show_access_code";

    static final String CARD_NOTE = "학생 화면에 [확인] 카드로 보여 줬음. 학생이 눌러야 실행됨";

    private ProposalParser() {
    }

    @SuppressWarnings("unchecked")
    public static Outcome parse(String tool, String text, boolean isError, JsonMapper json) {
        Map<String, Object> body;
        try {
            body = json.readValue(text, LinkedHashMap.class);
        } catch (RuntimeException e) {
            return new Outcome(PrivacyMask.mask(text), isError);
        }
        if (body == null) {
            return new Outcome(PrivacyMask.mask(text), isError);
        }
        // 입실 번호는 화면 카드로만 보여 준다. 어떤 도구가 실어 보내도(목록 안쪽 포함) 모델에는 넘기지 않는다
        boolean changed = stripAccessCodes(body);
        // 확인 토큰은 가리기 전에 떼어 둔다. 난수 토큰에 숫자 6개가 이어지면 "[입실 번호]"로 바뀌어 확인이 실패한다.
        // 토큰은 모델에 넘기지 않으니(카드로만 감) 가릴 필요도 없다
        Object token = isError ? null : body.remove("confirmToken");
        changed |= token != null;
        changed |= maskStrings(body);
        if (isError) {
            return new Outcome(changed ? json.writeValueAsString(body) : text, true);
        }
        if (ACCESS_CODE_TOOL.equals(tool)) {
            return accessCode(text, body, changed, json);
        }
        if (token == null) {
            return new Outcome(changed ? json.writeValueAsString(body) : text, false);
        }
        Object notes = body.get("notes");
        Object needs = body.get("needsParticipants");
        var card = new Confirmation(tool, token.toString(), String.valueOf(body.get("summary")),
                notes instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(),
                needs instanceof Number n ? n.intValue() : 0, String.valueOf(body.get("expiresAt")));
        body.put("confirmation", CARD_NOTE);
        return new Outcome(json.writeValueAsString(body), false, Optional.of(card));
    }

    /**
     * 혹시 mcp-server가 번호를 실어 보내도 모델에 넘기지 않게 accessCode는 지운다 (원래는 오지 않음).
     */
    private static Outcome accessCode(String text, Map<String, Object> body, boolean stripped, JsonMapper json) {
        Optional<AccessCodeCard> card = Boolean.TRUE.equals(body.get("cardShown"))
                && body.get("reservationId") instanceof Number id
                ? Optional.of(new AccessCodeCard(id.longValue())) : Optional.empty();
        String forModel = stripped ? json.writeValueAsString(body) : text;
        return new Outcome(forModel, false, Optional.empty(), card);
    }

    /** 문자열 값(안쪽 객체·목록 포함)에만 학번·전화번호·6자리 번호 가림을 건다. 숫자 값은 건드리지 않는다. 바꿨으면 true */
    @SuppressWarnings("unchecked")
    private static boolean maskStrings(Object node) {
        boolean changed = false;
        if (node instanceof Map<?, ?> map) {
            for (var entry : ((Map<Object, Object>) map).entrySet()) {
                if (entry.getValue() instanceof String s) {
                    String masked = PrivacyMask.mask(s);
                    if (!masked.equals(s)) {
                        entry.setValue(masked);
                        changed = true;
                    }
                } else {
                    changed |= maskStrings(entry.getValue());
                }
            }
        } else if (node instanceof List<?> list) {
            var items = (List<Object>) list;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof String s) {
                    String masked = PrivacyMask.mask(s);
                    if (!masked.equals(s)) {
                        items.set(i, masked);
                        changed = true;
                    }
                } else {
                    changed |= maskStrings(items.get(i));
                }
            }
        }
        return changed;
    }

    /** accessCode 키를 안쪽 객체·목록까지 모두 지운다. 하나라도 지웠으면 true */
    private static boolean stripAccessCodes(Object node) {
        boolean stripped = false;
        if (node instanceof Map<?, ?> map) {
            stripped = map.remove("accessCode") != null;
            for (Object value : map.values()) {
                stripped |= stripAccessCodes(value);
            }
        } else if (node instanceof List<?> list) {
            for (Object value : list) {
                stripped |= stripAccessCodes(value);
            }
        }
        return stripped;
    }
}
