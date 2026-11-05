package kr.ac.chosun.mspace.assistant.agent.chat.application;

import kr.ac.chosun.mspace.assistant.agent.chat.application.ConfirmationGateway.Relayed;

/**
 * 입실 번호 카드가 번호를 받는 길. mcp-server의 REST를 학생 헤더로 그대로 부른다. 모델은 거치지 않는다.
 * 대표자가 아니면 mcp-server가 403과 "대표자에게 문의하세요"를 준다.
 */
public interface AccessCodeGateway {

    /** @return mcp-server가 준 상태 코드와 본문. 닿지 못하면 503 TOOLS_UNAVAILABLE */
    Relayed accessCode(long reservationId, UserHeaders user);
}
