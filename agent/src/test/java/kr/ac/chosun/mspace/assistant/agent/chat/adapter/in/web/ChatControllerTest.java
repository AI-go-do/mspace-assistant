package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AccessCodeGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ReadTimetableImage;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ScheduleGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentLoop;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatJobProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatJobs;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConfirmationGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.DailyBriefing;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
    final ConversationMemory memory = mock(ConversationMemory.class);
    MockMvc mvc;
    final ReadTimetableImage timetable = mock(ReadTimetableImage.class);
    final DailyBriefing dailyBriefing = mock(DailyBriefing.class);

    @BeforeEach
    void setUp() {
        ChatJobProperties jobProps = new ChatJobProperties(0, 0, null, null, null);
        ChatJobs jobs = new ChatJobs(jobProps, Clock.systemUTC(), executor);
        AgentProperties props = new AgentProperties(null, List.of("Authorization"), 8, null, null, null, 0, 0);
        ChatController controller = new ChatController(agent, mock(ConfirmationGateway.class),
                mock(AccessCodeGateway.class), mock(ScheduleGateway.class), props, jobs, jobProps, memory,
                timetable, dailyBriefing);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(agent.run(any(UserHeaders.class), anyList(), anyString()))
                .thenReturn(new ChatResult("3번 방이 비어 있어요", List.of(), List.of(), 1, false, List.of("find_available_rooms"), List.of(), List.of()));
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
    @DisplayName("실패한 작업을 조회하면 FAILED와 오류 code·message가 온다 (내부 예외 문구는 내보내지 않는다)")
    void failedJobThenPoll() throws Exception {
        // given
        when(agent.run(any(UserHeaders.class), anyList(), anyString()))
                .thenThrow(new IllegalStateException("internal"));
        var accepted = mvc.perform(post("/api/agent/chat").header("Authorization", "Bearer owner")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andReturn();
        String jobId = JsonMapper.builder().build().readTree(accepted.getResponse().getContentAsString())
                .get("jobId").asString();

        // when
        executor.runAll();

        // then
        var polled = mvc.perform(get("/api/agent/chat/" + jobId).header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("AGENT_FAILED"))
                .andExpect(jsonPath("$.error.message").value("답을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요."))
                .andReturn();
        assertThat(polled.getResponse().getContentAsString()).doesNotContain("internal");
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

    @Test
    @DisplayName("새 대화(DELETE history)는 로그인한 학생의 기억만 지우고 204, 로그인 없으면 401")
    void clearHistory() throws Exception {
        // when
        mvc.perform(delete("/api/agent/chat/history").header("Authorization", "Bearer owner"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/agent/chat/history"))
                .andExpect(status().isUnauthorized());

        // then: 토큰 모양이 아니면 헤더 해시 키로 지운다 (키에 토큰 원문이 없다)
        verify(memory).clear(argThat(key -> key.startsWith("h:") && !key.contains("owner")));
        verifyNoMoreInteractions(memory);
    }

    @Test
    @DisplayName("채팅이 돌고 있는 동안은 지우지 못하고(409), 저장소에 닿지 못하면 503")
    void clearHistoryConflictsAndFails() throws Exception {
        // given: 접수만 하고 executor는 돌리지 않는다 → 진행 중
        mvc.perform(post("/api/agent/chat").header("Authorization", "Bearer owner")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted());

        // when / then
        mvc.perform(delete("/api/agent/chat/history").header("Authorization", "Bearer owner"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT_IN_PROGRESS"));
        verifyNoMoreInteractions(memory);

        // given: 다른 학생, Redis가 죽어 있음
        doThrow(new ConversationMemory.Unavailable()).when(memory).clear(anyString());
        mvc.perform(delete("/api/agent/chat/history").header("Authorization", "Bearer other"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEMORY_UNAVAILABLE"));
    }
    @Test
    @DisplayName("시간표 캡처 읽기: 로그인 헤더 + multipart image → 200 classes 초안. 저장은 하지 않는다(PUT은 따로)")
    void parseScheduleImage() throws Exception {
        // given
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        when(timetable.read(eq("image/png"), eq(png))).thenReturn(new ReadTimetableImage.Draft(
                List.of(new ReadTimetableImage.ClassDraft("MON", "09:00", "10:30", "자료구조")), 1, "읽지 못한 칸이 1개 있어요."));

        // when · then
        mvc.perform(multipart("/api/agent/schedule/parse").file(new MockMultipartFile("image", "t.png", "image/png", png))
                        .header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.classes[0].dayOfWeek").value("MON"))
                .andExpect(jsonPath("$.classes[0].name").value("자료구조"))
                .andExpect(jsonPath("$.dropped").value(1))
                .andExpect(jsonPath("$.note").value("읽지 못한 칸이 1개 있어요."));
    }

    @Test
    @DisplayName("시간표 캡처 읽기: 로그인 없으면 401이고 모델을 부르지 않는다. 잘못된 파일은 400 INVALID_IMAGE, 모델 장애는 503")
    void parseScheduleImageErrors() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile("image", "t.pdf", "application/pdf", new byte[] {1});

        mvc.perform(multipart("/api/agent/schedule/parse").file(pdf))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        // image 파트가 없어도 로그인 검사가 먼저, 로그인했으면 400 INVALID_IMAGE
        mvc.perform(multipart("/api/agent/schedule/parse"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/agent/schedule/parse").header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
        verifyNoMoreInteractions(timetable);

        // 5MB 초과(multipart 해석 단계 예외)는 전역 advice가 400 INVALID_IMAGE로
        var tooLarge = new UploadErrorAdvice().tooLarge(new org.springframework.web.multipart.MaxUploadSizeExceededException(5L * 1024 * 1024));
        org.assertj.core.api.Assertions.assertThat(tooLarge.getStatusCode().value()).isEqualTo(400);
        org.assertj.core.api.Assertions.assertThat(tooLarge.getBody()).containsEntry("code", "INVALID_IMAGE");

        when(timetable.read(eq("application/pdf"), any())).thenThrow(new ReadTimetableImage.InvalidImage("PNG, JPEG, WebP 사진만 읽을 수 있어요."));
        mvc.perform(multipart("/api/agent/schedule/parse").file(pdf).header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IMAGE"));

        when(timetable.read(eq("application/pdf"), any())).thenThrow(new LlmException("LLM_UNAVAILABLE", "사진을 읽지 못했어요."));
        mvc.perform(multipart("/api/agent/schedule/parse").file(pdf).header("Authorization", "Bearer owner"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"));
    }
    @Test
    @DisplayName("오늘 브리핑: 로그인 헤더로 GET → 200 text·hints, 없으면 401이고 도구를 부르지 않는다")
    void briefing() throws Exception {
        // given
        when(dailyBriefing.today(any(UserHeaders.class))).thenReturn(new DailyBriefing.Briefing("2026-11-06", "금",
                "2026-11-06T08:00:00+09:00", new DailyBriefing.Schedule(List.of(), null, "아직 넣은 시간표가 없어요."),
                new DailyBriefing.Reservations(List.of(), null), new DailyBriefing.Rooms(List.of("2번 방"), "10:00", null),
                "11월 6일 금요일 브리핑이에요. …", List.of("OPEN_SCHEDULE")));

        // when
        var ok = mvc.perform(get("/api/agent/briefing").header("Authorization", "Bearer owner"));
        var anonymous = mvc.perform(get("/api/agent/briefing"));

        // then
        ok.andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.text").value("11월 6일 금요일 브리핑이에요. …"))
                .andExpect(jsonPath("$.rooms.availableNames[0]").value("2번 방"))
                .andExpect(jsonPath("$.hints[0]").value("OPEN_SCHEDULE"));
        anonymous.andExpect(status().isUnauthorized());
        verify(dailyBriefing).today(any(UserHeaders.class));
        verifyNoMoreInteractions(dailyBriefing);
    }
}
