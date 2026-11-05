package kr.ac.chosun.mspace.assistant.mcp.confirm.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 연장하기 확인안에서 먼저 거르는 규칙 (application.yml의 mspace.continue). 최종 판단은 core가 한다(core
 * mspace.reservation.extension과 같은 값으로 둔다). 여기서 먼저 거르는 건 [확인]을 누른 뒤 실패하지 않게 하려는 것뿐이다.
 *
 * @param openBeforeMinutes 종료 몇 분 전부터 연장할 수 있나 (core open-before-minutes)
 * @param maxMinutes        한 번에 늘릴 수 있는 최대 분 (core max-minutes)
 * @param slotMinutes       늘리는 단위 (예약 칸 30분)
 */
@ConfigurationProperties("mspace.continue")
public record ContinueProperties(int openBeforeMinutes, int maxMinutes, int slotMinutes) {

    public ContinueProperties {
        openBeforeMinutes = openBeforeMinutes <= 0 ? 10 : openBeforeMinutes;
        maxMinutes = maxMinutes <= 0 ? 120 : maxMinutes;
        slotMinutes = slotMinutes <= 0 ? 30 : slotMinutes;
    }
}
