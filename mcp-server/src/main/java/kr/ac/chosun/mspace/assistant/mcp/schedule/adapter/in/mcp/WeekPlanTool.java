package kr.ac.chosun.mspace.assistant.mcp.schedule.adapter.in.mcp;

import kr.ac.chosun.mspace.assistant.mcp.common.Credentials;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.PlanWeek;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.WeekPlan;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/** 읽기 도구 week_plan (FR-AGT-102). 날마다 시간표 + 빈방을 한 번에. 설명 문장은 모델이 읽는다 */
@Component
class WeekPlanTool {

    private final PlanWeek plan;

    WeekPlanTool(PlanWeek plan) {
        this.plan = plan;
    }

    @McpTool(name = "week_plan",
            description = """
                    앞으로 7일 동안 날마다 "수업 끝나고 원하는 길이로 쓸 수 있는 가장 이른 시간" 하나를 돌려준다.
                    "이번 주에 수업 끝나고 2시간 쓸 수 있는 날", "언제 비어?", "다음 주 중에 스터디할 날 잡아 줘"처럼
                    하루가 아니라 여러 날을 묻는 말에 쓴다. 하루만 물으면 my_schedule·find_available_rooms를 쓴다.
                    시간표(my_schedule)와 빈방(find_available_rooms)을 서버가 날짜마다 이어 붙인 결과라 다시 부르지 않는다.
                    days[].option이 있으면 그날 roomName·startAt·endAt 그대로 제안하고, 없으면 reason을 전한다.
                    code가 NO_SCHEDULE이면 note를 그대로 전한다(시간표 없이 매일 09:00부터 본 결과). 시각은 서울 시간(+09:00).
                    범위는 from~to(오늘부터 7일)로 고정이라 그 밖의 날짜는 없다. 학생이 물은 범위와 겹치는 날만 답하고,
                    범위 밖은 아직 볼 수 없다고 말한다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    WeekPlan weekPlan(
            McpSyncRequestContext context,
            @McpToolParam(description = "원하는 길이(분). 30분 단위, 30~240. 없으면 120", required = false) Integer minutes,
            @McpToolParam(description = "사용할 사람 수 (대표자 포함). 방 인원 범위에 맞는 방만 본다", required = false) Integer headcount) {
        return plan.plan(Credentials.require(context), minutes, headcount);
    }
}
