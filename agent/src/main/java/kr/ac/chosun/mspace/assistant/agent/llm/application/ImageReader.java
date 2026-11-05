package kr.ac.chosun.mspace.assistant.agent.llm.application;

/**
 * 그림 한 장을 모델에 보여 주고 글(JSON)을 받는 포트. 시간표 캡처 읽기에 쓴다 (설계 5장 다음 단계 ①).
 * 그림은 저장하지 않고 요청에만 실린다. 로그에도 남기지 않는다.
 */
public interface ImageReader {

    /**
     * @param instruction 무엇을 어떤 JSON으로 적을지 (시스템 지시문)
     * @param mimeType    image/png | image/jpeg | image/webp
     * @param image       원본 바이트
     * @return 모델이 적은 글. JSON을 요구했으면 JSON 문자열
     * @throws LlmException 키 없음·한도·장애. 코드는 LlmClient와 같다
     */
    String read(String instruction, String mimeType, byte[] image);

    /** 그림을 못 읽는 모델(Ollama 기본 등). 호출하면 LLM_NOT_CONFIGURED */
    ImageReader UNSUPPORTED = (instruction, mimeType, image) -> {
        throw new LlmException("LLM_NOT_CONFIGURED", "이 모델은 사진을 읽지 못해요. 시간표를 직접 입력해 주세요.");
    };
}
