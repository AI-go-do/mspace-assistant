package kr.ac.chosun.mspace.assistant.mcp.common;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예약 코어 연결 설정 (application.yml의 mspace.core).
 *
 * @param baseUrl        예약 코어 주소 (예: http://core:8080)
 * @param forwardHeaders 에이전트 요청에서 코어로 넘길 헤더. 로그인 붙기 전까지 core는 X-User-Id를 쓴다
 * @param timeout        코어 응답을 기다리는 최대 시간. 넘으면 도구 오류로 돌려준다
 */
@ConfigurationProperties("mspace.core")
public record CoreProperties(String baseUrl, List<String> forwardHeaders, Duration timeout) {
}
