package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConversationJsonTest {

    @Test
    @DisplayName("학생·비서(도구 호출 포함)·도구 결과가 JSON으로 갔다가 그대로 돌아온다")
    void roundTrip() {
        // given
        List<LlmMessage> conversation = List.of(
                new LlmMessage.User("내일 빈방 [학번]"),
                new LlmMessage.Assistant("", List.of(new ToolCall("c1", "find_available_rooms",
                        Map.of("date", "2026-11-03", "headcount", 3), "sig"))),
                new LlmMessage.ToolResult("c1", "find_available_rooms", "{\"rooms\":[]}", false),
                new LlmMessage.Assistant("없어요\n\n(예약 시스템 15:00 조회 기준)", List.of()));

        // when
        String json = ConversationJson.write(conversation);
        List<LlmMessage> back = ConversationJson.read(json);

        // then
        assertThat(back).isEqualTo(conversation);
    }

    @Test
    @DisplayName("깨진 JSON·모르는 종류·빈 값은 예외 없이 빈 대화로 본다")
    void brokenIsEmpty() {
        assertThat(ConversationJson.read(null)).isEmpty();
        assertThat(ConversationJson.read("")).isEmpty();
        assertThat(ConversationJson.read("{not json")).isEmpty();
        assertThat(ConversationJson.read("[{\"t\":\"x\"}]")).isEmpty();
    }
}
