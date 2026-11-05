package kr.ac.chosun.mspace.assistant.agent.chat;

import static kr.ac.chosun.mspace.assistant.agent.chat.AgentLoopTest.CLOCK;
import static kr.ac.chosun.mspace.assistant.agent.chat.AgentLoopTest.FREE;
import static kr.ac.chosun.mspace.assistant.agent.chat.AgentLoopTest.ME;
import static kr.ac.chosun.mspace.assistant.agent.chat.AgentLoopTest.say;
import static kr.ac.chosun.mspace.assistant.agent.chat.AgentLoopTest.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.chat.AgentLoopTest.FakeTools;
import kr.ac.chosun.mspace.assistant.agent.chat.AgentLoopTest.ScriptedLlm;
import kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory.InMemoryConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentLoop;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.MemberKey;
import kr.ac.chosun.mspace.assistant.agent.chat.application.MemoryProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ToolGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmReply;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolCall;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 서버가 대화 맥락을 기억하는 흐름 (설계 4장) */
class ConversationMemoryTest {

    static final MemoryProperties MEMORY = new MemoryProperties(true, null, Duration.ofHours(24), 0);

    static AgentLoop loop(ScriptedLlm llm, ToolGateway tools, ConversationMemory memory, int maxTurns, int maxChars) {
        return new AgentLoop(llm, tools, new AgentProperties(null, null, 8, null, null, null, 0, maxTurns), CLOCK,
                memory, new MemoryProperties(true, null, Duration.ofHours(24), maxChars));
    }

    @Test
    @DisplayName("두 번째 말에는 서버가 기억한 앞선 대화(도구 호출·결과·꼬리말 답)가 모델에 같이 간다")
    void remembersToolCallsAcrossChats() {
        // given
        var memory = new InMemoryConversationMemory(MEMORY, CLOCK);
        var tools = new FakeTools(Map.of("find_available_rooms", new ToolGateway.Outcome(FREE, false)));
        var llm = new ScriptedLlm().then(tool("find_available_rooms", Map.of("date", "2026-11-03")))
                .then(say("2번 방이 비어 있어요")).then(say("아까 조회한 결과예요"));
        var loop = loop(llm, tools, memory, 20, 20_000);

        // when
        loop.run(ME, List.of(), "내일 빈방 20231234");
        var second = loop.run(ME, List.of(), "그걸 어떻게 알아?");

        // then
        assertThat(second.reply()).isEqualTo("아까 조회한 결과예요");
        List<LlmMessage> seen = llm.seen.get(2);
        assertThat(seen).hasSize(5); // 학생 → 비서(도구 호출) → 도구 결과 → 비서(꼬리말 답) → 학생
        assertThat(seen.get(0)).isEqualTo(new LlmMessage.User("내일 빈방 [학번]")); // 가린 채로 기억
        assertThat(seen.get(1)).isInstanceOf(LlmMessage.Assistant.class);
        assertThat(((LlmMessage.Assistant) seen.get(1)).toolCalls()).hasSize(1);
        assertThat(seen.get(2)).isInstanceOf(LlmMessage.ToolResult.class);
        assertThat(((LlmMessage.Assistant) seen.get(3)).text()).isEqualTo("2번 방이 비어 있어요\n\n(예약 시스템 15:00 조회 기준)");
        assertThat(seen.get(4)).isEqualTo(new LlmMessage.User("그걸 어떻게 알아?"));
        assertThat(tools.called).hasSize(1); // 두 번째는 도구 없이 기억으로 답했다
    }

    @Test
    @DisplayName("서버 기억이 있으면 화면이 보낸 history는 쓰지 않고, 없을 때만 쓴다")
    void serverMemoryWinsOverClientHistory() {
        // given
        var memory = new InMemoryConversationMemory(MEMORY, CLOCK);
        var tools = new FakeTools(Map.of());
        var llm = new ScriptedLlm().then(say("첫 답")).then(say("둘째 답"));
        var loop = loop(llm, tools, memory, 20, 20_000);
        List<LlmMessage> client = List.of(new LlmMessage.User("화면이 든 옛말"), new LlmMessage.Assistant("옛 답", List.of()));

        // when
        loop.run(ME, client, "첫 말");
        loop.run(ME, client, "둘째 말");

        // then
        assertThat(llm.seen.get(0)).containsExactly(new LlmMessage.User("화면이 든 옛말"),
                new LlmMessage.Assistant("옛 답", List.of()), new LlmMessage.User("첫 말"));
        assertThat(llm.seen.get(1)).containsExactly(new LlmMessage.User("화면이 든 옛말"),
                new LlmMessage.Assistant("옛 답", List.of()), new LlmMessage.User("첫 말"),
                new LlmMessage.Assistant("첫 답", List.of()), new LlmMessage.User("둘째 말"));
    }

    @Test
    @DisplayName("다른 학생의 기억은 섞이지 않고, 지우면 처음부터 시작한다")
    void separateByMemberAndClear() {
        // given
        var memory = new InMemoryConversationMemory(MEMORY, CLOCK);
        var tools = new FakeTools(Map.of());
        var llm = new ScriptedLlm();
        var loop = loop(llm, tools, memory, 20, 20_000);
        var other = new UserHeaders(Map.of("X-User-Id", "2"));

        // when
        loop.run(ME, List.of(), "내 말");
        loop.run(other, List.of(), "남의 말");
        memory.clear(MemberKey.of(ME));
        loop.run(ME, List.of(), "새 대화");

        // then
        assertThat(llm.seen.get(1)).containsExactly(new LlmMessage.User("남의 말"));
        assertThat(llm.seen.get(2)).containsExactly(new LlmMessage.User("새 대화"));
    }

    @Test
    @DisplayName("모델이 답을 못 만들면(예외) 이번 차례는 기억하지 않는다 — 다시 보내면 같은 자리에서 시작")
    void doesNotRememberFailedTurn() {
        // given
        var memory = new InMemoryConversationMemory(MEMORY, CLOCK);
        var tools = new FakeTools(Map.of());
        var failing = new ScriptedLlm() {
            int n;

            @Override
            public LlmReply chat(String system,
                    List<LlmMessage> conversation, List<ToolSpec> t) {
                super.chat(system, conversation, t);
                if (n++ == 0) {
                    throw new LlmException("LLM_UNAVAILABLE", "응답하지 않아요");
                }
                return say("됐어요");
            }
        };
        var loop = loop(failing, tools, memory, 20, 20_000);

        // when
        assertThatThrownBy(() -> loop.run(ME, List.of(), "첫 말")).isInstanceOf(LlmException.class);
        loop.run(ME, List.of(), "첫 말");

        // then
        assertThat(failing.seen.get(1)).containsExactly(new LlmMessage.User("첫 말"));
    }

    @Test
    @DisplayName("기억은 학생 차례 수·글자 수 상한을 넘으면 오래된 차례부터 버리고, 자르는 곳은 늘 학생 말 앞이다")
    void trimsAtUserBoundary() {
        // given
        var a = new LlmMessage.User("1");
        var b = new LlmMessage.Assistant("", List.of(new ToolCall(
                "c1", "find_available_rooms", Map.of())));
        var c = new LlmMessage.ToolResult("c1", "find_available_rooms", "x".repeat(100), false);
        var d = new LlmMessage.Assistant("답1", List.of());
        var e = new LlmMessage.User("2");
        var f = new LlmMessage.Assistant("답2", List.of());
        List<LlmMessage> all = List.of(a, b, c, d, e, f);

        // when / then
        assertThat(AgentLoop.trim(all, 2, 20_000)).containsExactly(a, b, c, d, e, f);
        assertThat(AgentLoop.trim(all, 1, 20_000)).containsExactly(e, f);            // 차례 수 초과 → 도구 짝째로 버림
        assertThat(AgentLoop.trim(all, 20, 50)).containsExactly(e, f);               // 글자 수 초과
        assertThat(AgentLoop.trim(List.of(c, d, e, f), 20, 20_000)).containsExactly(e, f); // 학생 말로 시작하지 않는 앞부분은 버림
    }

    @Test
    @DisplayName("TTL이 지난 기억은 잊는다")
    void forgetsAfterTtl() {
        // given
        var clock = new AgentLoopTest.SlowClock(Duration.ofHours(13));
        var memory = new InMemoryConversationMemory(MEMORY, clock);

        // when
        memory.save("m:1", List.of(new LlmMessage.User("안녕")));
        List<LlmMessage> soon = memory.load("m:1");   // 저장 13시간 뒤
        List<LlmMessage> later = memory.load("m:1");  // 저장 26시간 뒤

        // then
        assertThat(soon).hasSize(1);
        assertThat(later).isEmpty();
    }
}
