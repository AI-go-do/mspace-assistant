package kr.ac.chosun.mspace.assistant.mcp.common;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예약 코어 연결 설정 (application.yml의 mspace.core).
 *
 * @param baseUrl        예약 코어 주소 (예: http://core:8080)
 * @param forwardHeaders 에이전트 요청에서 코어로 넘길 헤더. 기본은 Authorization만, X-User-Id는 local 프로필에서만
 * @param timeout        코어 응답을 기다리는 최대 시간. 넘으면 도구 오류로 돌려준다
 * @param requireHttps   true면 baseUrl이 https가 아닐 때 시작하지 않는다(#44). 학생 토큰이 평문으로 나가지 않게.
 *                       compose 안(http://core:8080, 같은 호스트의 도커 네트워크)과 localhost는 예외라 기본 false.
 *                       core가 다른 서버에 있어 공용 구간을 지나면 CORE_REQUIRE_HTTPS=true
 */
@ConfigurationProperties("mspace.core")
public record CoreProperties(String baseUrl, List<String> forwardHeaders, Duration timeout, boolean requireHttps) {

    /**
     * @throws IllegalStateException requireHttps인데 주소가 https도, 내 컴퓨터(localhost)도 아닐 때. 시작 시점에 터져서 배포가 멈춘다
     */
    public void checkTransport() {
        if (!requireHttps || baseUrl == null) {
            return;
        }
        java.net.URI uri = java.net.URI.create(baseUrl);
        String host = uri.getHost();
        boolean loopback = host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]"));
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !loopback) {
            throw new IllegalStateException("mspace.core.require-https=true인데 core 주소가 https가 아니에요: " + uri.getScheme()
                    + "://" + host + " (학생 토큰이 평문으로 나가요. https 주소를 쓰거나 같은 호스트 안이면 CORE_REQUIRE_HTTPS=false)");
        }
    }
}
