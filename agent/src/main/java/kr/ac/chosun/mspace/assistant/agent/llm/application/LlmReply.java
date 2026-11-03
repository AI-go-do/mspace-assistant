package kr.ac.chosun.mspace.assistant.agent.llm.application;

import java.util.List;

/** 모델의 한 번 답. toolCalls가 비어 있으면 학생에게 보낼 최종 답이다 */
public record LlmReply(String text, List<ToolCall> toolCalls) {

    public LlmReply {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }
}
