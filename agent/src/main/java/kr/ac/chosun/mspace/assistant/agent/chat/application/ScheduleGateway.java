package kr.ac.chosun.mspace.assistant.agent.chat.application;

import kr.ac.chosun.mspace.assistant.agent.chat.application.ConfirmationGateway.Relayed;

/**
 * 내 시간표 화면이 시간표를 넣고·보고·지우는 길. mcp-server의 /api/schedule/me를 학생 헤더로 그대로 부른다.
 * 모델은 거치지 않는다(과목명·시각은 my_schedule 도구로만 모델에 간다).
 */
public interface ScheduleGateway {

    /**
     * @param method GET | PUT | DELETE
     * @param body   PUT일 때 화면이 보낸 JSON 그대로. 나머지는 null
     * @return mcp-server가 준 상태 코드와 본문. 닿지 못하면 503 TOOLS_UNAVAILABLE
     */
    Relayed relay(String method, UserHeaders user, String body);
}
