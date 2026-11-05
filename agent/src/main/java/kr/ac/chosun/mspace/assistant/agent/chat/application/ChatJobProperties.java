package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 채팅 접수·결과 조회(폴링) 설정 (application.yml의 mspace.agent.jobs).
 *
 * @param workers       에이전트 반복을 동시에 돌리는 스레드 수
 * @param queueCapacity 스레드가 다 차 있을 때 기다릴 수 있는 접수 수. 넘으면 503 AGENT_BUSY
 * @param resultTtl     끝난 결과를 보관하는 시간. 지나면 조회해도 404
 * @param pendingTtl    끝나지 않은 접수를 보관하는 최대 시간 (멈춘 작업이 메모리에 남지 않게)
 * @param pollAfter     화면이 다음 조회까지 기다릴 시간 (응답에 힌트로 넣는다)
 */
@ConfigurationProperties("mspace.agent.jobs")
public record ChatJobProperties(int workers, int queueCapacity, Duration resultTtl, Duration pendingTtl,
                                Duration pollAfter) {

    public ChatJobProperties {
        workers = workers <= 0 ? 4 : workers;
        queueCapacity = queueCapacity <= 0 ? 20 : queueCapacity;
        resultTtl = resultTtl == null ? Duration.ofMinutes(5) : resultTtl;
        pendingTtl = pendingTtl == null ? Duration.ofMinutes(5) : pendingTtl;
        pollAfter = pollAfter == null ? Duration.ofSeconds(1) : pollAfter;
    }
}
