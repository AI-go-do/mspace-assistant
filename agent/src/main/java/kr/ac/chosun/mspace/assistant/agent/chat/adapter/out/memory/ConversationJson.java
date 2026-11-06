package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolCall;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대화(LlmMessage 목록) ↔ JSON. Redis에 문자열 하나로 넣는다.
 * sealed 인터페이스라 종류를 {@code t}(u·a·r)로 적는다. 모델 회사별 모양(Gemini parts 등)과는 무관한 우리 모양이다.
 */
final class ConversationJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Line>> LINES = new TypeReference<>() {
    };

    private ConversationJson() {
    }

    /** t: u(학생) | a(비서) | r(도구 결과). 쓰지 않는 칸은 null */
    record Line(String t, String text, List<Call> calls, String callId, String name, String json, Boolean isError) {
    }

    record Call(String id, String name, Map<String, Object> arguments, String signature) {
    }

    static String write(List<LlmMessage> conversation) {
        List<Line> lines = new ArrayList<>(conversation.size());
        for (LlmMessage m : conversation) {
            lines.add(switch (m) {
                case LlmMessage.User(String text) -> new Line("u", text, null, null, null, null, null);
                case LlmMessage.Assistant(String text, var toolCalls) -> new Line("a", text, toolCalls.stream()
                        .map(c -> new Call(c.id(), c.name(), c.arguments(), c.signature())).toList(), null, null, null, null);
                case LlmMessage.ToolResult(String callId, String name, String json, boolean isError) ->
                        new Line("r", null, null, callId, name, json, isError);
            });
        }
        return JSON.writeValueAsString(lines);
    }

    /** 모양이 깨졌으면(옛 버전 등) 빈 목록. 기억은 없어도 되는 것이라 예외로 대화를 막지 않는다 */
    static List<LlmMessage> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<LlmMessage> out = new ArrayList<>();
            for (Line l : JSON.readValue(json, LINES)) {
                switch (l.t()) {
                    case "u" -> out.add(new LlmMessage.User(l.text()));
                    case "a" -> out.add(new LlmMessage.Assistant(l.text(), l.calls() == null ? List.of() : l.calls().stream()
                            .map(c -> new ToolCall(c.id(), c.name(), c.arguments(), c.signature())).toList()));
                    case "r" -> out.add(new LlmMessage.ToolResult(l.callId(), l.name(), l.json(), Boolean.TRUE.equals(l.isError())));
                    default -> {
                        return List.of();
                    }
                }
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
