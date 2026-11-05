package kr.ac.chosun.mspace.assistant.mcp.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #44: 학생 토큰이 평문 http로 나가는 설정이면 시작하지 않는다 */
class CorePropertiesTest {

    static CoreProperties props(String url, boolean requireHttps) {
        return new CoreProperties(url, List.of("Authorization"), Duration.ofSeconds(3), requireHttps);
    }

    @Test
    @DisplayName("require-https=true: https 주소와 localhost(http)는 통과, 다른 호스트의 http는 시작 실패")
    void requireHttps() {
        // given · when · then
        assertThatCode(() -> props("https://dev-mspace.java21.net", true).checkTransport()).doesNotThrowAnyException();
        assertThatCode(() -> props("http://localhost:8080", true).checkTransport()).doesNotThrowAnyException();
        assertThatCode(() -> props("http://127.0.0.1:8080", true).checkTransport()).doesNotThrowAnyException();
        assertThatThrownBy(() -> props("http://core:8080", true).checkTransport())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("https가 아니에요").hasMessageContaining("core");
        assertThatThrownBy(() -> props("http://203.0.113.10:8080", true).checkTransport()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("require-https=false(기본, compose 안 http://core:8080): 검사하지 않는다")
    void defaultAllowsDockerNetwork() {
        assertThatCode(() -> props("http://core:8080", false).checkTransport()).doesNotThrowAnyException();
    }
}
