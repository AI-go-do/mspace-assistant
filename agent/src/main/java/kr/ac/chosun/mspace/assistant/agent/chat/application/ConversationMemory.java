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

    /**
     * 학생이 "새 대화"를 눌렀을 때. 대화와 미룬 글을 모두 지운다. 지우지 못했으면 {@link Unavailable} — 지운 척 204를 주면
     * 저장소가 돌아왔을 때 옛 대화가 되살아난다
     */
    void clear(String memberKey);

    /**
     * 채팅 밖에서 생긴 글(확인 카드 결과)을 미뤄 둔다. 다음 채팅이 {@link #takeNotes}로 꺼내 대화 앞에 붙인다.
     * 대화 자체를 읽고 고쳐 저장하지 않는 이유: 같은 학생의 채팅이 돌고 있으면 그 채팅이 끝날 때 대화를 통째로 저장해서
     * 중간에 끼운 글이 사라진다. 미뤄 둔 글은 따로 쌓이니 어느 쪽이 먼저 끝나도 잃지 않는다. 실패는 예외 없이 넘어간다
     */
    default void addNote(String memberKey, List<LlmMessage> note) {
    }

    /** 미뤄 둔 글을 쌓인 순서대로 꺼내고 비운다. 없거나 실패하면 빈 목록 */
    default List<LlmMessage> takeNotes(String memberKey) {
        return List.of();
    }

    /** 저장소에 닿지 못해 지우지 못함. 읽기·저장 실패는 기억 없이 넘어가지만, 지우기 실패는 학생에게 알린다 */
    class Unavailable extends RuntimeException {
        public Unavailable() {
            super("대화 기록을 지우지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
    }

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
