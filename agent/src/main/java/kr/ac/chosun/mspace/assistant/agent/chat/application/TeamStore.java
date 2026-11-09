package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.List;

/**
 * 학생별 팀 보관 (FR-AGT-105). 키는 대화 맥락과 같은 {@link MemberKey}, TTL 없음(설정값처럼 둔다).
 * 읽기 실패는 빈 목록으로 넘어가고(채팅은 팀 없이 계속), 저장 실패만 {@link ConversationMemory.Unavailable}로 알린다
 */
public interface TeamStore {

    /** 만든 순서. 없거나 못 읽으면 빈 목록 */
    List<Team> load(String memberKey);

    /** 바꾸기 전에 읽을 때. 못 읽으면 빈 목록 대신 {@link ConversationMemory.Unavailable} (빈 목록으로 덮어써 팀을 잃지 않게) */
    default List<Team> loadStrict(String memberKey) {
        return load(memberKey);
    }

    /** 통째로 바꾼다 */
    void save(String memberKey, List<Team> teams);

    TeamStore NONE = new TeamStore() {
        @Override
        public List<Team> load(String memberKey) {
            return List.of();
        }

        @Override
        public void save(String memberKey, List<Team> teams) {
            // 저장소 없음 (테스트)
        }
    };
}
