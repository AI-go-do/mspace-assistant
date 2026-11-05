package kr.ac.chosun.mspace.assistant.mcp.schedule.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * my_schedule 도구 결과. 모델에는 과목명과 시각만 간다.
 *
 * @param date           조회한 날짜
 * @param dayOfWeek      요일 (월~일, 한국어 한 글자)
 * @param code           시간표 자체가 없으면 NO_SCHEDULE, 있으면 null
 * @param classes        그날 수업 (시작 순). 없으면 빈 목록
 * @param lastClassEndAt 그날 마지막 수업이 끝나는 시각. 수업이 없으면 null
 * @param searchFromAt   빈방을 찾기 시작할 시각 = 끝 + 여유(설정값)를 다음 예약 칸으로 올림. 수업이 없으면 null
 * @param bufferMinutes  searchFromAt 계산에 쓴 여유 (분)
 * @param note           학생에게 그대로 전할 문장
 */
public record DaySchedule(LocalDate date, String dayOfWeek, String code, List<ClassOfDay> classes,
                          OffsetDateTime lastClassEndAt, OffsetDateTime searchFromAt, int bufferMinutes, String note) {

    public record ClassOfDay(String name, OffsetDateTime startAt, OffsetDateTime endAt) {
    }
}
