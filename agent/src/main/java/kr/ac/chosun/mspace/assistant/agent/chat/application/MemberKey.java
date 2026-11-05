package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대화 맥락을 저장할 때 쓰는 학생 키.
 * <ol>
 *   <li>Authorization: Bearer JWT의 sub(회원 id, core JwtTokens가 넣음). 서명은 확인하지 않는다: 게이트웨이가 이미 확인했고
 *       agent는 밖으로 열리지 않는다. 권한 검사는 여전히 core가 한다(비서는 맥락만 잇는다)</li>
 *   <li>로컬 프로필의 임시 X-User-Id</li>
 *   <li>둘 다 없으면 헤더 전체의 SHA-256 ({@link ChatJobs#ownerKey}) — 토큰이 바뀌면 대화도 끊긴다</li>
 * </ol>
 * 키에 토큰 원문은 들어가지 않고, 로그에도 남기지 않는다.
 */
public final class MemberKey {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MemberKey() {
    }

    public static String of(UserHeaders user) {
        for (Map.Entry<String, String> e : user.values().entrySet()) {
            if ("authorization".equalsIgnoreCase(e.getKey())) {
                String sub = subject(e.getValue());
                if (sub != null) {
                    return "m:" + sub;
                }
            }
        }
        for (Map.Entry<String, String> e : user.values().entrySet()) {
            if ("x-user-id".equalsIgnoreCase(e.getKey())) {
                return "u:" + e.getValue().trim();
            }
        }
        return "h:" + ChatJobs.ownerKey(user);
    }

    /** "Bearer a.b.c"에서 b(payload)의 sub. 모양이 아니면 null */
    static String subject(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String[] parts = authorization.substring(7).trim().split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode sub = JSON.readTree(new String(payload, StandardCharsets.UTF_8)).get("sub");
            return sub == null || !sub.isTextual() || sub.asString().isBlank() ? null : sub.asString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
