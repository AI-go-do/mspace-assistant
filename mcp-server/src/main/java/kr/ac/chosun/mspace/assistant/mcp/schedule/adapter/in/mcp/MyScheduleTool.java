package kr.ac.chosun.mspace.assistant.mcp.schedule.adapter.in.mcp;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.Credentials;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.DaySchedule;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.MySchedule;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/** 읽기 도구 my_schedule (FR-AGT-102). 학생이 직접 넣은 시간표만 본다. 설명 문장은 모델이 읽는다 */
@Component
class MyScheduleTool {

    private final MySchedule schedule;

    MyScheduleTool(MySchedule schedule) {
        this.schedule = schedule;
    }

    @McpTool(name = "my_schedule",
            description = """
                    로그인한 학생이 직접 넣은 수업 시간표에서 그날 수업을 돌려준다. "오늘 수업 뭐 있어?",
                    "수업 끝나고 빈방", "마지막 수업 30분 전에 방 잡아 줘"처럼 수업 시각이 필요한 말에 먼저 쓴다.
                    classes는 그날 수업(name, startAt, endAt), lastClassEndAt은 마지막 수업이 끝나는 시각이다.
                    searchFromAt은 "수업 끝나고"의 시작 시각(끝 + 여유 bufferMinutes를 30분 칸으로 올림)이라
                    시간을 직접 계산하지 말고 find_available_rooms의 from에 그대로(HH:mm) 넣는다.
                    "끝나기 30분 전"처럼 다른 기준을 말하면 lastClassEndAt·startAt으로 계산하되 30분 칸에 맞춘다.
                    code가 NO_SCHEDULE이면 시간표가 없는 것이니 note를 그대로 전하고 수업 시각을 지어내지 않는다.
                    classes가 비어 있으면 그날 수업이 없는 것이다. 시각은 서울 시간(+09:00).""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    DaySchedule mySchedule(
            McpSyncRequestContext context,
            @McpToolParam(description = "볼 날짜, YYYY-MM-DD (예: 2026-11-06). 없으면 오늘", required = false) String date) {
        return schedule.of(Credentials.require(context), parseDate(date));
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new CoreApiException("INVALID_REQUEST", "날짜는 YYYY-MM-DD 형식이어야 해요.");
        }
    }
}
