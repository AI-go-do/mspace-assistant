package kr.ac.chosun.mspace.assistant.agent.chat.application;

/**
 * 학생이 [확인]을 누르면 mcp-server 확인 API로 그대로 넘긴다. 모델은 거치지 않는다.
 * 결과(입실 번호 포함)는 학생 화면으로만 간다.
 */
public interface ConfirmationGateway {

    /**
     * @param body 확인 카드에서 받은 값(참여자 학번 등) JSON. 없으면 "{}"
     * @return mcp-server가 준 상태 코드와 본문. 닿지 못하면 503 TOOLS_UNAVAILABLE
     */
    Relayed confirm(String token, UserHeaders user, String body);

    record Relayed(int status, String json) {
    }
}
