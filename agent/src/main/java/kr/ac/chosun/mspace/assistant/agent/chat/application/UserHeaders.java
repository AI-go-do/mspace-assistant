package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.Map;

/** 학생 본인의 인증 헤더. mcp-server → core로 그대로 넘어간다. 값은 로그·모델에 절대 넣지 않는다 */
public record UserHeaders(Map<String, String> values) {

    public UserHeaders {
        values = Map.copyOf(values);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public String toString() {
        return "UserHeaders" + values.keySet();
    }
}
