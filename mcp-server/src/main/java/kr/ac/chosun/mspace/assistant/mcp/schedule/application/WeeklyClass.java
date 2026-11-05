package kr.ac.chosun.mspace.assistant.mcp.schedule.application;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * 시간표의 수업 한 칸. 요일·시작·끝·과목명만 둔다 (강의실·교수명·학번은 저장하지 않는다, 설계 5장).
 *
 * @param dayOfWeek 요일 (MONDAY…SUNDAY)
 * @param startTime 시작 시각 (서울 시간)
 * @param endTime   끝 시각. 시작보다 늦다 (자정을 넘는 수업은 받지 않는다)
 * @param name      과목명. 모델에 그대로 간다
 */
public record WeeklyClass(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, String name) {
}
