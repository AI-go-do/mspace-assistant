package kr.ac.chosun.mspace.assistant.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberKeyTest {

    static String jwt(String payloadJson) {
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return "Bearer eyJhbGciOiJIUzI1NiJ9." + b64 + ".sig";
    }

    @Test
    @DisplayName("토큰의 sub(회원 id)가 키가 된다 — 토큰이 새로 발급돼도 같은 학생이면 같은 대화를 잇는다")
    void subjectFromJwt() {
        // given
        var first = new UserHeaders(Map.of("Authorization", jwt("{\"sub\":\"42\",\"exp\":1}")));
        var refreshed = new UserHeaders(Map.of("Authorization", jwt("{\"sub\":\"42\",\"exp\":2}")));

        // when / then
        assertThat(MemberKey.of(first)).isEqualTo("m:42").isEqualTo(MemberKey.of(refreshed));
        assertThat(MemberKey.of(first)).doesNotContain("eyJ");
    }

    @Test
    @DisplayName("토큰 모양이 아니면 X-User-Id, 그것도 없으면 헤더 해시를 쓴다 (원문은 키에 남지 않는다)")
    void fallbacks() {
        // given
        var local = new UserHeaders(Map.of("X-User-Id", "7"));
        var opaque = new UserHeaders(Map.of("Authorization", "Bearer owner"));
        var broken = new UserHeaders(Map.of("Authorization", jwt("{\"exp\":1}")));

        // when / then
        assertThat(MemberKey.of(local)).isEqualTo("u:7");
        assertThat(MemberKey.of(opaque)).startsWith("h:").doesNotContain("owner");
        assertThat(MemberKey.of(broken)).startsWith("h:");
        assertThat(MemberKey.subject("Bearer not.base64!!.x")).isNull();
        assertThat(MemberKey.subject(null)).isNull();
    }
}
