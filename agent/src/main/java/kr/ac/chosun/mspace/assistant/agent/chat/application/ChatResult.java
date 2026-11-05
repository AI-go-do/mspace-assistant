package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.List;

/**
 * 채팅 한 번의 결과 (화면용).
 *
 * @param reply         학생에게 보여 줄 답
 * @param confirmations 쓰기 도구가 만든 확인안. 화면은 이걸로 [확인] 카드를 그린다 (토큰은 모델에 보이지 않음)
 * @param accessCodeCards 입실 번호 카드. 화면이 GET /api/agent/reservations/{id}/access-code로 번호를 받아 그린다
 * @param toolCalls     이번에 부른 도구 수 (측정용)
 * @param stoppedByLimit 도구 호출 최대 횟수를 넘어 멈췄으면 true
 * @param tools         부른 도구 이름(부른 순서, 중복 포함). 측정(#9)이 "맞는 도구를 골랐는지" 보는 데 쓴다. 인자는 넣지 않는다
 * @param hints         화면에 줄 신호. {@link #HINT_OPEN_SCHEDULE}: 시간표가 없어서(NO_SCHEDULE) 답했으니 [내 시간표] 버튼을 보여 달라
 * @param suggestions   답 아래 "다음 말" 버튼 글(최대 3개, 없으면 빈 목록). 누르면 그 글을 그대로 채팅으로 보낸다.
 *                      마지막 도구 결과로 규칙이 정한다({@link QuickReplies}). 확인 카드가 있으면 비어 있다
 */
public record ChatResult(String reply, List<Confirmation> confirmations, List<AccessCodeCard> accessCodeCards,
                         int toolCalls, boolean stoppedByLimit, List<String> tools, List<String> hints,
                         List<String> suggestions) {

    /** my_schedule이 NO_SCHEDULE을 돌려줬다. 화면은 답 아래에 [내 시간표 넣기] 버튼을 그린다 (설계 5장 다음 단계 ③) */
    public static final String HINT_OPEN_SCHEDULE = "OPEN_SCHEDULE";

    /** needsParticipants: 확인 카드에서 받을 학번 수 */
    public record Confirmation(String tool, String token, String summary, List<String> notes, int needsParticipants,
                               String expiresAt) {
    }

    /** 번호는 들어 있지 않다. 카드를 그릴 때 화면이 따로 받는다 (모델·대화 기록에 번호가 남지 않게) */
    public record AccessCodeCard(long reservationId) {
    }
}
