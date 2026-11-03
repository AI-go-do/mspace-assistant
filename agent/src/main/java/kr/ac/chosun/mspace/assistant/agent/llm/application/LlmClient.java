package kr.ac.chosun.mspace.assistant.agent.llm.application;

import java.util.List;

/**
 * 모델 포트. Gemini(시연)·Ollama(학교 도입) 어댑터가 이 뒤에 있고, 설정값 하나(mspace.llm.provider)로 바꾼다.
 * 도구를 여러 번 이어 부르는 건 모델 기능이 아니라 에이전트 반복이 한다. 이 포트는 한 번 묻고 한 번 답만 한다.
 */
public interface LlmClient {

    /**
     * @param system       시스템 지시문 (비서 역할, 안전 규칙)
     * @param conversation 지금까지의 대화. 개인정보(학번·전화번호)는 이미 가린 상태여야 한다
     * @param tools        모델이 부를 수 있는 도구 목록 (MCP tools/list 결과)
     */
    LlmReply chat(String system, List<LlmMessage> conversation, List<ToolSpec> tools);
}
