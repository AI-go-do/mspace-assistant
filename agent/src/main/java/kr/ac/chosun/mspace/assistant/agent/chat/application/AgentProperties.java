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
 * @param rules          지시문에 넣는 예약 규칙 숫자. core 설정과 같은 값으로 둔다 (TEAM.md 4장: 숫자는 설정값)
 * @param chatTimeout    대화 한 번 전체 시간 상한. 넘으면 모델을 더 부르지 않고 멈춘다 (결과는 폴링으로 받는다)
 * @param maxMessageChars 학생 말 한 번의 최대 글자 수. 넘으면 400
 * @param maxHistoryTurns 모델에 넘길 앞선 대화 수. 넘으면 오래된 것부터 버린다
 */
@ConfigurationProperties("mspace.agent")
public record AgentProperties(String mcpServerUrl, List<String> forwardHeaders, int maxToolCalls, Duration timeout,
                              Rules rules, Duration chatTimeout, int maxMessageChars, int maxHistoryTurns) {

    public AgentProperties {
        mcpServerUrl = mcpServerUrl == null ? "http://localhost:8090" : mcpServerUrl;
        forwardHeaders = forwardHeaders == null ? List.of("Authorization") : List.copyOf(forwardHeaders);
        maxToolCalls = maxToolCalls <= 0 ? 8 : maxToolCalls;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        rules = rules == null ? new Rules(0, 0, 0) : rules;
        chatTimeout = chatTimeout == null ? Duration.ofSeconds(45) : chatTimeout;
        maxMessageChars = maxMessageChars <= 0 ? 1000 : maxMessageChars;
        maxHistoryTurns = maxHistoryTurns <= 0 ? 20 : maxHistoryTurns;
    }

    /** 0 이하는 기본값 (30분 단위, 최대 2시간, 7일 안) */
    public record Rules(int slotMinutes, int maxHours, int advanceDays) {

        public Rules {
            slotMinutes = slotMinutes <= 0 ? 30 : slotMinutes;
            maxHours = maxHours <= 0 ? 2 : maxHours;
            advanceDays = advanceDays <= 0 ? 7 : advanceDays;
        }
    }
}
