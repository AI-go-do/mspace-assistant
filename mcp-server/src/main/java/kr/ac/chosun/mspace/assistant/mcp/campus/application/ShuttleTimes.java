package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.time.LocalDate;
import java.util.List;

/**
 * campus_shuttle 도구 결과.
 *
 * @param after  이 시각(HH:mm) 뒤 출발만 봤다
 * @param code   시간표가 아예 없으면 NO_TIMETABLE, 있으면 null
 * @param routes 노선별 다음 출발 (그날 운행하는 노선만)
 * @param basis  꼭 같이 전할 한 줄 (시간표 기준, 실시간 아님)
 * @param note   학생에게 그대로 전할 문장. 없으면 null
 */
public record ShuttleTimes(LocalDate date, String dayOfWeek, String after, String code, List<RouteTimes> routes,
                           String basis, String note) {

    /**
     * @param next 다음 출발 시각 HH:mm (가까운 순, 최대 3개). 비면 그날 남은 차가 없다
     * @param last    그날 마지막 출발 HH:mm (정해진 시각이 하나도 없으면 null)
     * @param running 이 시각에 걸친 시각 없는 운행 (예: "07:40~17:00 수시 운행(5~10분 간격)"). 없으면 null
     */
    public record RouteTimes(String name, String stop, List<String> next, String last, String running) {
    }
}
