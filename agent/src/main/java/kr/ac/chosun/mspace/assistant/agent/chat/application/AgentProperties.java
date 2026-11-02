package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 에이전트 설정 (application.yml의 mspace.agent).
 *
 * @param mcpServerUrl   mcp-server 주소 (MCP는 {url}/mcp, 확인 실행은 {url}/api/confirmations)
 * @param forwardHeaders 학생 요청에서 mcp-server로 넘길 헤더 (학생 본인 토큰)
 * @param maxToolCalls   대화 한 번에 도구를 부를 수 있는 최대 횟수. 넘으면 멈추고 알린다 (CLAUDE.md 4장)
 * @param timeout        mcp-server 호출 최대 시간
 */
@ConfigurationProperties("mspace.agent")
public record AgentProperties(String mcpServerUrl, List<String> forwardHeaders, int maxToolCalls, Duration timeout) {

    public AgentProperties {
        mcpServerUrl = mcpServerUrl == null ? "http://localhost:8090" : mcpServerUrl;
        forwardHeaders = forwardHeaders == null ? List.of("Authorization", "X-User-Id") : List.copyOf(forwardHeaders);
        maxToolCalls = maxToolCalls <= 0 ? 8 : maxToolCalls;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }
}
