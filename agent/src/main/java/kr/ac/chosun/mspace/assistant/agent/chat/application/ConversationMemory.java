package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.List;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;

/**
 * 학생별 대화 맥락 보관 (11/5 멘토 제안: 맥락·메모리를 서버가 지킨다).
 * <p>
 * 화면이 들고 보내던 {@code history}를 서버가 대신 기억한다. 도구 호출·결과까지 남기므로 모델이 "내가 조회했었다"를
 * 안다(트러블슈팅 TS-09의 근본 원인). 저장하는 글은 모두 {@link PrivacyMask}로 가린 뒤의 것이고, 원문은 남지 않는다.
 * <ul>
 *   <li>키는 {@link MemberKey}(토큰의 회원 id). 토큰이 새로 발급돼도 같은 학생이면 같은 대화를 잇는다</li>
 *   <li>어댑터: 메모리(기본, agent 1대) · Redis(결정 뒤, TTL은 저장소가 지운다)</li>
 * </ul>
 */
public interface ConversationMemory {

    /** 보관된 대화. 없거나 지났으면 빈 목록 (예외 없음) */
    List<LlmMessage> load(String memberKey);

    /** 대화 전체를 바꿔 저장한다. 호출자가 이미 길이를 줄였다 */
    void save(String memberKey, List<LlmMessage> conversation);

    /** 학생이 "새 대화"를 눌렀을 때 */
    void clear(String memberKey);

    /** 저장하지 않는 구현 (설정으로 끌 때·테스트) */
    ConversationMemory NONE = new ConversationMemory() {
        @Override
        public List<LlmMessage> load(String memberKey) {
            return List.of();
        }

        @Override
        public void save(String memberKey, List<LlmMessage> conversation) {
        }

        @Override
        public void clear(String memberKey) {
        }
    };
}
