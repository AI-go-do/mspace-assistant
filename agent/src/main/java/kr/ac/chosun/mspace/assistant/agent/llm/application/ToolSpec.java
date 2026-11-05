package kr.ac.chosun.mspace.assistant.agent.llm.application;

import java.util.Map;

/** 모델에 알려 줄 도구 한 개. inputSchema는 MCP tools/list의 JSON Schema 그대로 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
}
