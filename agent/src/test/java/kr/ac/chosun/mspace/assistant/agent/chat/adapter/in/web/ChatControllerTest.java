package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AccessCodeGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentLoop;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatJobProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatJobs;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConfirmationGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class ChatControllerTest {

    static final String BODY = "{\"message\":\"내일 오후 2시 빈방\",\"history\":[]}";

    /** 접수와 실행 사이를 손으로 나누려고 직접 실행하는 executor */
    static class ManualExecutor extends AbstractExecutorService {
        final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.poll().run();
            }
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    final ManualExecutor executor = new ManualExecutor();
    final AgentLoop agent = mock(AgentLoop.class);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        ChatJobProperties jobProps = new ChatJobProperties(0, 0, null, null, null);
        ChatJobs jobs = new ChatJobs(jobProps, Clock.systemUTC(), executor);
        AgentProperties props = new AgentProperties(null, List.of("Authorization"), 8, null, null, null, 0, 0);
        ChatController controller = new ChatController(agent, mock(ConfirmationGateway.class),
                mock(AccessCodeGateway.class), props, jobs, jobProps);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(agent.run(any(UserHeaders.class), anyList(), anyString()))
                .thenReturn(new ChatResult("3번 방이 비어 있어요", List.of(), List.of(), 1, false));
    }

    @Test
    @DisplayName("채팅을 보내면 기다리지 않고 202 + jobId를 받고, 조회하면 PENDING → DONE 순서로 결과가 온다")
    void submitThenPoll() throws Exception {
        // when
        var accepted = mvc.perform(post("/api/agent/chat").header("Authorization", "Bearer owner")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.pollAfterMs").value(1000))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        String jobId = JsonMapper.builder().build().readTree(accepted.getResponse().getContentAsString())
                .get("jobId").asString();

        // then
        assertThat(accepted.getResponse().getHeader("Location")).isEqualTo("/api/agent/chat/" + jobId);
        mvc.perform(get("/api/agent/chat/" + jobId).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        executor.runAll();
        mvc.perform(get("/api/agent/chat/" + jobId).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.result.reply").value("3번 방이 비어 있어요"));
    }

    @Test
    @DisplayName("남의 토큰으로 결과를 조회하면 404, 로그인 없이 보내면 401")
    void ownerOnly() throws Exception {
        // given
        var accepted = mvc.perform(post("/api/agent/chat").header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn();
        String jobId = JsonMapper.builder().build().readTree(accepted.getResponse().getContentAsString())
                .get("jobId").asString();
        executor.runAll();

        // when, then
        mvc.perform(get("/api/agent/chat/" + jobId).header("Authorization", "Bearer other"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_JOB_NOT_FOUND"));
        mvc.perform(get("/api/agent/chat/" + jobId)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("앞 질문이 끝나기 전에 또 보내면 409 CHAT_IN_PROGRESS")
    void secondChatWhileRunning() throws Exception {
        // given
        mvc.perform(post("/api/agent/chat").header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isAccepted());

        // when, then
        mvc.perform(post("/api/agent/chat").header("Authorization", "Bearer owner")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT_IN_PROGRESS"));
    }

    @Test
    @DisplayName("빈 말은 접수하지 않고 바로 400")
    void blankMessage() throws Exception {
        mvc.perform(post("/api/agent/chat").header("Authorization", "Bearer owner")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\" \"}"))
                .andExpect(status().isBadRequest());
        assertThat(executor.tasks).isEmpty();
    }
}
