package kr.ac.chosun.mspace.assistant.agent.chat.application;

/**
 * 학생별 비서 성향 보관. 키는 대화 맥락과 같은 {@link MemberKey}. 대화와 달리 TTL 없이 둔다(설정값이지 대화가 아니다).
 * 읽기 실패는 기본 성향으로 넘어가고, 저장 실패만 {@link ConversationMemory.Unavailable}로 알린다(바뀐 척하지 않는다)
 */
public interface PersonaStore {

    /** 없거나 못 읽으면 {@link Persona#DEFAULT} */
    Persona load(String memberKey);

    void save(String memberKey, Persona persona);

    /** 저장하지 않는 구현 (테스트) */
    PersonaStore NONE = new PersonaStore() {
        @Override
        public Persona load(String memberKey) {
            return Persona.DEFAULT;
        }

        @Override
        public void save(String memberKey, Persona persona) {
        }
    };
}
