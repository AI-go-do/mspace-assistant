package kr.ac.chosun.mspace.assistant.agent.llm.application;

/**
 * 모델 호출 실패. message는 학생에게 보여 줄 문장이다(키·주소를 넣지 않는다).
 *
 * @param code LLM_NOT_CONFIGURED(키 없음), LLM_RATE_LIMITED(호출 한도), LLM_UNAVAILABLE(응답 없음·오류)
 */
public class LlmException extends RuntimeException {

    private final String code;

    public LlmException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
