package kr.ac.chosun.mspace.assistant.agent.llm.application;

import java.util.Map;

/**
 * 모델이 부르겠다고 한 도구 한 번.
 *
 * @param id        결과를 짝지을 때 쓴다(모델이 안 주면 어댑터가 만든다)
 * @param signature 모델이 다음 요청에 그대로 돌려받아야 하는 값(Gemini의 thoughtSignature). 없으면 null
 */
public record ToolCall(String id, String name, Map<String, Object> arguments, String signature) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this(id, name, arguments, null);
    }
}
