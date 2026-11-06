package kr.ac.chosun.mspace.assistant.mcp.room.adapter.in.mcp;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.Credentials;
import kr.ac.chosun.mspace.assistant.mcp.room.application.AvailableRooms;
import kr.ac.chosun.mspace.assistant.mcp.room.application.FindAvailableRooms;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/** 읽기 도구 find_available_rooms (FR-ROOM-001). 설명 문장은 모델이 읽는다 */
@Component
class FindAvailableRoomsTool {

    private final FindAvailableRooms rooms;

    FindAvailableRoomsTool(FindAvailableRooms rooms) {
        this.rooms = rooms;
    }

    @McpTool(name = "find_available_rooms",
            description = """
                    M-SPACE 방(1~5번)의 빈 시간을 날짜 하나 기준으로 찾는다. "내일 오후 2시 빈방",
                    "금요일 저녁에 4명 쓸 방" 같은 질문, 그리고 예약을 제안하기 전에 쓴다.
                    결과는 방마다 이어진 빈 구간(free) 목록이다. 시각은 서울 시간(+09:00).
                    조회할 수 있는 날짜는 오늘부터 6일 뒤까지다. 예약은 30분 단위, 1회 최대 2시간이다.
                    from·to를 주면 그 시간 전체가 비어 있는 방만 나온다. 자정을 넘기면(예: 23:00~01:00)
                    시작 날짜를 date로 주고 to를 01:00으로 준다. note가 있으면 학생에게 함께 전한다.
                    그 시간에 빈방이 없으면 alternatives(같은 날, 같은 길이, 가장 가까운 시간 최대 3개)가 온다.
                    직접 계산하지 말고 alternatives를 그대로 "이 시간은 어때요?"로 제안한다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    AvailableRooms findAvailableRooms(
            McpSyncRequestContext context,
            @McpToolParam(description = "조회할 날짜, YYYY-MM-DD (예: 2026-11-03)") String date,
            @McpToolParam(description = "이 시각부터 비어 있어야 함, HH:mm 24시간 (예: 14:00). 없으면 하루 전체", required = false) String from,
            @McpToolParam(description = "이 시각까지 비어 있어야 함, HH:mm (예: 16:00)", required = false) String to,
            @McpToolParam(description = "사용할 사람 수 (대표자 포함). 방 인원 범위에 맞는 방만 남긴다", required = false) Integer headcount) {
        return rooms.find(parseDate(date), parseTime(from), parseTime(to), headcount, Credentials.require(context));
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw invalid();
        }
    }

    private static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw invalid();
        }
    }

    private static CoreApiException invalid() {
        return new CoreApiException("INVALID_REQUEST", "날짜는 YYYY-MM-DD, 시각은 HH:mm 형식이어야 해요.");
    }
}
