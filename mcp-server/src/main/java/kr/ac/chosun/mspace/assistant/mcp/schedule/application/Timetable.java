package kr.ac.chosun.mspace.assistant.mcp.schedule.application;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 학생 한 명의 주간 시간표. 통째로 바꾼다(PUT). 요일·시작 순으로 정렬해 둔다.
 *
 * @param classes   수업 목록
 * @param updatedAt 마지막으로 넣은 시각 (+09:00). 화면에 "11/5 18:40에 넣은 시간표"로 보여 준다
 */
public record Timetable(List<WeeklyClass> classes, OffsetDateTime updatedAt) {

    public Timetable {
        classes = classes.stream()
                .sorted(Comparator.comparing(WeeklyClass::dayOfWeek).thenComparing(WeeklyClass::startTime))
                .toList();
    }
}
