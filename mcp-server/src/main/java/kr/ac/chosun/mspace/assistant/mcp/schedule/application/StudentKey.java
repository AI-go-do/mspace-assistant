package kr.ac.chosun.mspace.assistant.mcp.schedule.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시간표를 저장할 학생 키. agent의 MemberKey와 같은 규칙이라 같은 학생이면 같은 키가 나온다.
 * <ol>
 *   <li>Authorization: Bearer JWT의 sub → "m:&lt;sub&gt;". 서명은 확인하지 않는다(게이트웨이가 확인, mcp-server는 밖으로 안 열림)</li>
 *   <li>로컬 프로필의 임시 X-User-Id → "u:&lt;id&gt;"</li>
 * </ol>
 * 둘 다 없으면 401. 대화 기억과 달리 시간표는 오래 남으므로 토큰 해시처럼 바뀌는 키로는 저장하지 않는다.
 */
public final class StudentKey {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StudentKey() {
    }

    public static String of(UserCredential credential) {
        for (Map.Entry<String, String> e : credential.headers().entrySet()) {
            if ("authorization".equalsIgnoreCase(e.getKey())) {
                String sub = subject(e.getValue());
                if (sub != null) {
                    return "m:" + sub;
                }
            }
        }
        for (Map.Entry<String, String> e : credential.headers().entrySet()) {
            if ("x-user-id".equalsIgnoreCase(e.getKey()) && !e.getValue().isBlank()) {
                return "u:" + e.getValue().trim();
            }
        }
        throw new CoreApiException("UNAUTHORIZED", "로그인이 필요합니다", 401);
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
