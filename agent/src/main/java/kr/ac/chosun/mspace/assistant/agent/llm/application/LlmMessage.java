package kr.ac.chosun.mspace.assistant.agent.llm.application;

import java.util.List;

/** 대화 한 줄. 모델 회사마다 모양이 달라서 어댑터가 각자 바꾼다 */
public sealed interface LlmMessage {

    record User(String text) implements LlmMessage {
    }

    /** 모델의 답. 도구 호출이 있으면 text는 비어 있을 수 있다 */
    record Assistant(String text, List<ToolCall> toolCalls) implements LlmMessage {

        public Assistant {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    /**
     * 도구 실행 결과.
     *
     * @param json    도구 결과 본문(JSON 문자열). 입실 번호 등은 mcp-server가 이미 뺐다
     * @param isError 도구 오류면 true (모델은 json 안의 문장을 학생에게 전한다)
     */
    record ToolResult(String callId, String name, String json, boolean isError) implements LlmMessage {
    }
}
