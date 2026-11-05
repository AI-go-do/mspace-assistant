package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.List;

/**
 * 채팅 한 번의 결과 (화면용).
 *
 * @param reply         학생에게 보여 줄 답
 * @param confirmations 쓰기 도구가 만든 확인안. 화면은 이걸로 [확인] 카드를 그린다 (토큰은 모델에 보이지 않음)
 * @param toolCalls     이번에 부른 도구 수 (측정용)
 * @param stoppedByLimit 도구 호출 최대 횟수를 넘어 멈췄으면 true
 */
public record ChatResult(String reply, List<Confirmation> confirmations, int toolCalls, boolean stoppedByLimit) {

    /** needsParticipants: 확인 카드에서 받을 학번 수 */
    public record Confirmation(String tool, String token, String summary, List<String> notes, int needsParticipants,
                               String expiresAt) {
    }
}
