package kr.ac.chosun.mspace.assistant.agent.llm.application;

import java.util.List;

/**
 * 모델의 한 번 답. toolCalls가 비어 있으면 학생에게 보낼 최종 답이다.
 *
 * @param usage 이번 호출에 쓴 토큰 수. 모델이 알려 주지 않으면 {@link Usage#UNKNOWN}. 모델 평가(scripts/eval)가 비용을 잰다
 */
public record LlmReply(String text, List<ToolCall> toolCalls, Usage usage) {

    public LlmReply {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? Usage.UNKNOWN : usage;
    }

    public LlmReply(String text, List<ToolCall> toolCalls) {
        this(text, toolCalls, Usage.UNKNOWN);
    }

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }

    /**
     * 토큰 수. 학생 말·답 내용은 없고 숫자만 있어 로그·평가 결과에 남겨도 된다.
     *
     * @param inputTokens  보낸 쪽(지시문·대화·도구 목록) 토큰
     * @param outputTokens 받은 쪽(답·도구 호출, 생각 토큰 포함) 토큰
     */
    public record Usage(int inputTokens, int outputTokens) {

        public static final Usage UNKNOWN = new Usage(-1, -1);

        public boolean known() {
            return inputTokens >= 0 && outputTokens >= 0;
        }
    }
}
