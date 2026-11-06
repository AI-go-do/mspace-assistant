package kr.ac.chosun.mspace.assistant.mcp.schedule.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * week_plan 도구 결과: 앞으로 며칠 동안 날마다 "수업 끝나고 원하는 길이로 쓸 수 있는 가장 이른 시간" 하나.
 * 모델이 my_schedule·find_available_rooms를 날짜마다 번갈아 부르다 횟수 한도(8번)에 걸리지 않게 서버가 한 번에 계산한다.
 *
 * @param from      첫 날(오늘, 서울)
 * @param to        마지막 날
 * @param minutes   원한 길이 (분)
 * @param code      시간표가 없으면 NO_SCHEDULE (그래도 날마다 planFromTime부터 본 결과를 준다), 있으면 null
 * @param days      날짜 순. 쓸 수 있는 날은 option이 있고, 없으면 reason에 이유
 * @param note      학생에게 그대로 전할 문장
 */
public record WeekPlan(LocalDate from, LocalDate to, int minutes, String code, List<DayPlan> days, String note) {

    /**
     * @param lastClassEndAt 그날 마지막 수업 끝. 수업이 없으면 null
     * @param searchFromAt   빈방을 보기 시작한 시각 (수업 끝 + 여유를 칸으로 올림, 수업 없으면 planFromTime)
     * @param option         가장 이른 한 칸. 없으면 null
     * @param reason         option이 없는 이유. 있으면 null
     */
    public record DayPlan(LocalDate date, String dayOfWeek, int classCount, OffsetDateTime lastClassEndAt,
                          OffsetDateTime searchFromAt, Option option, String reason) {
    }

    public record Option(int roomId, String roomName, OffsetDateTime startAt, OffsetDateTime endAt) {
    }
}
