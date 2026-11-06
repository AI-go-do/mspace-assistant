package kr.ac.chosun.mspace.assistant.mcp.schedule.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시간표 설정 (application.yml의 mspace.schedule).
 *
 * @param store         memory(기본, mcp-server 1대, 재시작하면 사라짐) | redis (spring.data.redis.url)
 * @param bufferMinutes 마지막 수업이 끝난 뒤 방으로 옮겨 갈 여유. 기획서 기본 30분
 * @param slotMinutes   예약 칸. searchFromAt을 이 단위로 올린다 (core 규칙과 같게)
 * @param maxClasses    한 학생이 넣을 수 있는 수업 칸 수 상한 (잘못된 대량 입력 방지)
 * @param maxNameChars  과목명 글자 수 상한
 * @param planDays      week_plan이 보는 날 수 (오늘부터). core 조회 범위와 같은 7
 * @param planFromTime  수업이 없는 날(또는 시간표가 없을 때) week_plan이 보기 시작하는 시각. 기본 09:00
 */
@ConfigurationProperties("mspace.schedule")
public record ScheduleProperties(String store, int bufferMinutes, int slotMinutes, int maxClasses, int maxNameChars,
                                 int planDays, java.time.LocalTime planFromTime) {

    public ScheduleProperties {
        store = store == null || store.isBlank() ? "memory" : store.trim().toLowerCase();
        bufferMinutes = bufferMinutes < 0 ? 30 : bufferMinutes;
        slotMinutes = slotMinutes <= 0 ? 30 : slotMinutes;
        maxClasses = maxClasses <= 0 ? 40 : maxClasses;
        maxNameChars = maxNameChars <= 0 ? 30 : maxNameChars;
        planDays = planDays <= 0 ? 7 : planDays;
        planFromTime = planFromTime == null ? java.time.LocalTime.of(9, 0) : planFromTime;
    }
}
