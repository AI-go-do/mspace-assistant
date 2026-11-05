package kr.ac.chosun.mspace.assistant.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatJobsTest {

    static final UserHeaders OWNER = new UserHeaders(Map.of("Authorization", "Bearer owner"));
    static final UserHeaders OTHER = new UserHeaders(Map.of("Authorization", "Bearer other"));
    static final ChatResult RESULT = new ChatResult("빈방이 있어요", List.of(), List.of(), 1, false);

    /** 시간을 손으로 옮기는 시계 */
    static class MovableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-04T05:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 나중에 손으로 실행하는 executor: 진행 중 상태를 만들 때 쓴다 */
    static class ManualExecutor extends AbstractExecutorService {
        final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.poll().run();
            }
        }

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
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

    final MovableClock clock = new MovableClock();
    final ChatJobProperties props = new ChatJobProperties(1, 1, Duration.ofMinutes(5), Duration.ofMinutes(3), null);

    @Test
    @DisplayName("접수하면 바로 PENDING이고, 끝나면 같은 학생이 DONE 결과를 받는다")
    void acceptsThenReturnsResult() {
        // given
        ManualExecutor executor = new ManualExecutor();
        ChatJobs jobs = new ChatJobs(props, clock, executor);

        // when
        var accepted = (ChatJobs.Submitted.Accepted) jobs.submit(OWNER, () -> RESULT);

        // then
        assertThat(jobs.find(accepted.jobId(), OWNER)).get()
                .extracting(ChatJobs.View::status).isEqualTo(ChatJobs.Status.PENDING);
        executor.runAll();
        var view = jobs.find(accepted.jobId(), OWNER).orElseThrow();
        assertThat(view.status()).isEqualTo(ChatJobs.Status.DONE);
        assertThat(view.result()).isEqualTo(RESULT);
        assertThat(accepted.jobId()).startsWith("j_").hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("남의 jobId는 결과(확인 토큰 포함)를 볼 수 없고, 없는 jobId와 똑같이 비어 있다")
    void otherUserCannotRead() {
        // given
        ChatJobs jobs = new ChatJobs(props, clock, direct());
        var accepted = (ChatJobs.Submitted.Accepted) jobs.submit(OWNER, () -> RESULT);

        // when, then
        assertThat(jobs.find(accepted.jobId(), OTHER)).isEmpty();
        assertThat(jobs.find("j_없는번호", OWNER)).isEmpty();
        assertThat(jobs.find(null, OWNER)).isEmpty();
    }

    @Test
    @DisplayName("같은 학생이 앞 질문이 끝나기 전에 또 보내면 CHAT_IN_PROGRESS, 끝난 뒤엔 다시 받는다")
    void oneRunningChatPerUser() {
        // given
        ManualExecutor executor = new ManualExecutor();
        ChatJobs jobs = new ChatJobs(props, clock, executor);
        jobs.submit(OWNER, () -> RESULT);

        // when
        var second = jobs.submit(OWNER, () -> RESULT);
        var otherUser = jobs.submit(OTHER, () -> RESULT);

        // then
        assertThat(second).isInstanceOfSatisfying(ChatJobs.Submitted.Rejected.class,
                r -> assertThat(r.code()).isEqualTo("CHAT_IN_PROGRESS"));
        assertThat(otherUser).isInstanceOf(ChatJobs.Submitted.Accepted.class);
        executor.runAll();
        assertThat(jobs.submit(OWNER, () -> RESULT)).isInstanceOf(ChatJobs.Submitted.Accepted.class);
    }

    @Test
    @DisplayName("같은 학생이 동시에 50번 보내도 접수는 한 건만 된다")
    void concurrentSubmitsFromSameUser() throws Exception {
        // given
        ManualExecutor executor = new ManualExecutor();
        ChatJobs jobs = new ChatJobs(props, clock, executor);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        // when
        for (int i = 0; i < 50; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (jobs.submit(OWNER, () -> RESULT) instanceof ChatJobs.Submitted.Accepted) {
                    accepted.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // then
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(jobs.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("스레드와 대기열이 다 차면 AGENT_BUSY로 바로 거절하고, 거절된 접수는 남지 않는다")
    void rejectsWhenQueueFull() throws Exception {
        // given: 스레드 1개 + 대기열 1개
        CountDownLatch release = new CountDownLatch(1);
        ChatJobs jobs = new ChatJobs(props, clock);
        jobs.submit(new UserHeaders(Map.of("Authorization", "a")), () -> await(release));
        jobs.submit(new UserHeaders(Map.of("Authorization", "b")), () -> await(release));

        // when
        var third = jobs.submit(new UserHeaders(Map.of("Authorization", "c")), () -> RESULT);

        // then
        assertThat(third).isInstanceOfSatisfying(ChatJobs.Submitted.Rejected.class,
                r -> assertThat(r.code()).isEqualTo("AGENT_BUSY"));
        assertThat(jobs.size()).isEqualTo(2);
        release.countDown();
        jobs.close();
    }

    @Test
    @DisplayName("모델 오류는 code를 그대로, 그 밖의 오류는 메시지를 숨기고 AGENT_FAILED로 남긴다")
    void failures() {
        // given
        ChatJobs jobs = new ChatJobs(props, clock, direct());

        // when
        var llm = (ChatJobs.Submitted.Accepted) jobs.submit(OWNER, () -> {
            throw new LlmException("LLM_RATE_LIMITED", "잠시 뒤 다시 시도해 주세요.");
        });
        var other = (ChatJobs.Submitted.Accepted) jobs.submit(OTHER, () -> {
            throw new IllegalStateException("학번 20231234 포함된 내부 메시지");
        });

        // then
        assertThat(jobs.find(llm.jobId(), OWNER).orElseThrow().error())
                .isEqualTo(new ChatJobs.Failure("LLM_RATE_LIMITED", "잠시 뒤 다시 시도해 주세요."));
        var failed = jobs.find(other.jobId(), OTHER).orElseThrow();
        assertThat(failed.status()).isEqualTo(ChatJobs.Status.FAILED);
        assertThat(failed.error().code()).isEqualTo("AGENT_FAILED");
        assertThat(failed.error().message()).doesNotContain("20231234");
    }

    @Test
    @DisplayName("끝난 결과는 보관 시간이 지나면 사라지고, 멈춘 접수도 일정 시간 뒤 지워져 다시 보낼 수 있다")
    void expires() {
        // given
        ManualExecutor executor = new ManualExecutor();
        ChatJobs jobs = new ChatJobs(props, clock, executor);
        var done = (ChatJobs.Submitted.Accepted) jobs.submit(OTHER, () -> RESULT);
        executor.runAll();
        jobs.submit(OWNER, () -> RESULT); // 실행하지 않아 계속 PENDING

        // when
        clock.now = clock.now.plus(Duration.ofMinutes(6));

        // then
        assertThat(jobs.find(done.jobId(), OTHER)).isEmpty();
        assertThat(jobs.submit(OWNER, () -> RESULT)).isInstanceOf(ChatJobs.Submitted.Accepted.class);
    }

    @Test
    @DisplayName("대기열에서 접수 보관 시간을 넘겨 지워진 작업은 나중에 차례가 와도 모델을 부르지 않는다")
    void expiredQueuedJobDoesNotRun() {
        // given: 접수만 되고 아직 작업 스레드가 집지 않음
        ManualExecutor executor = new ManualExecutor();
        ChatJobs jobs = new ChatJobs(props, clock, executor);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        jobs.submit(OWNER, () -> {
            calls.incrementAndGet();
            return RESULT;
        });
        clock.now = clock.now.plus(Duration.ofMinutes(4));
        jobs.submit(OTHER, () -> RESULT); // 접수하면서 오래된 접수를 정리한다

        // when
        executor.runAll();

        // then
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("그 사이 접수·조회가 없어도, 대기열에서 접수 보관 시간을 넘긴 작업은 작업 스레드가 집을 때 돌리지 않는다")
    void expiredQueuedJobDoesNotRunWithoutOtherCalls() {
        // given: 접수만 되고 아직 작업 스레드가 집지 않음. 다른 submit/find 호출은 없다
        ManualExecutor executor = new ManualExecutor();
        ChatJobs jobs = new ChatJobs(props, clock, executor);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        var accepted = (ChatJobs.Submitted.Accepted) jobs.submit(OWNER, () -> {
            calls.incrementAndGet();
            return RESULT;
        });
        clock.now = clock.now.plus(Duration.ofMinutes(6));

        // when
        executor.runAll();

        // then: 돌지 않고, 학생은 다시 보낼 수 있다
        assertThat(calls).hasValue(0);
        assertThat(jobs.find(accepted.jobId(), OWNER)).isEmpty();
        assertThat(jobs.submit(OWNER, () -> RESULT)).isInstanceOf(ChatJobs.Submitted.Accepted.class);
    }

    @Test
    @DisplayName("이미 돌고 있는 작업은 접수 보관 시간이 지나도 지우지 않아, 같은 학생 작업이 두 개 돌지 않는다")
    void runningJobIsNotSwept() {
        // given
        ChatJobs jobs = new ChatJobs(props, clock, direct());
        java.util.concurrent.atomic.AtomicReference<ChatJobs.Submitted> duringRun = new java.util.concurrent.atomic.AtomicReference<>();

        // when: 작업이 도는 동안 시간이 4분 흐르고 같은 학생이 또 보낸다
        var first = (ChatJobs.Submitted.Accepted) jobs.submit(OWNER, () -> {
            clock.now = clock.now.plus(Duration.ofMinutes(4));
            duringRun.set(jobs.submit(OWNER, () -> RESULT));
            return RESULT;
        });

        // then
        assertThat(duringRun.get()).isInstanceOf(ChatJobs.Submitted.Rejected.class);
        assertThat(jobs.find(first.jobId(), OWNER)).get().extracting(ChatJobs.View::status).isEqualTo(ChatJobs.Status.DONE);
    }

    @Test
    @DisplayName("작업 시작과 만료 정리가 동시에 일어나도 둘 중 하나만 이겨서, 돌기 시작한 작업은 지워지지 않는다")
    void startAndSweepRace() throws Exception {
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 300; round++) {
                // given: 시작 전 접수가 보관 시간을 넘김
                ManualExecutor executor = new ManualExecutor();
                ChatJobs jobs = new ChatJobs(props, clock, executor);
                AtomicInteger calls = new AtomicInteger();
                var accepted = (ChatJobs.Submitted.Accepted) jobs.submit(OWNER, () -> {
                    calls.incrementAndGet();
                    return RESULT;
                });
                clock.now = clock.now.plus(Duration.ofMinutes(4));
                CountDownLatch go = new CountDownLatch(1);

                // when: 작업 스레드가 집는 것과 조회(정리)가 동시에
                var run = racers.submit(() -> { go.await(); executor.runAll(); return null; });
                var sweep = racers.submit(() -> { go.await(); return jobs.find(accepted.jobId(), OWNER); });
                go.countDown();
                run.get(5, TimeUnit.SECONDS);
                sweep.get(5, TimeUnit.SECONDS);

                // then: 모델을 불렀다면 결과가 남아 있고, 안 불렀다면 접수도 지워져 있다
                assertThat(jobs.find(accepted.jobId(), OWNER).isPresent()).as("round %d", round)
                        .isEqualTo(calls.get() == 1);
            }
        } finally {
            racers.shutdownNow();
        }
    }

    @Test
    @DisplayName("작업 중 Error가 나도 FAILED로 끝나고 그 학생은 바로 다시 보낼 수 있다")
    void errorStillFinishes() {
        // given
        ManualExecutor executor = new ManualExecutor();
        ChatJobs jobs = new ChatJobs(props, clock, executor);
        var accepted = (ChatJobs.Submitted.Accepted) jobs.submit(OWNER, () -> {
            throw new StackOverflowError();
        });

        // when: Error는 작업 스레드 밖으로 그대로 나간다(스레드풀이 그 스레드를 새로 만든다)
        assertThatThrownBy(executor::runAll).isInstanceOf(StackOverflowError.class);

        // then
        assertThat(jobs.find(accepted.jobId(), OWNER)).get().extracting(ChatJobs.View::status)
                .isEqualTo(ChatJobs.Status.FAILED);
        assertThat(jobs.submit(OWNER, () -> RESULT)).isInstanceOf(ChatJobs.Submitted.Accepted.class);
    }

    @Test
    @DisplayName("주인 키는 토큰 원문이 아니라 해시이고, 헤더 이름 대소문자와 순서에 흔들리지 않는다")
    void ownerKeyIsHash() {
        // given
        var sameUserOtherCase = new UserHeaders(Map.of("authorization", "Bearer owner"));

        // when
        String key = ChatJobs.ownerKey(OWNER);

        // then
        assertThat(key).hasSize(64).doesNotContain("owner");
        assertThat(ChatJobs.ownerKey(sameUserOtherCase)).isEqualTo(key);
        assertThat(ChatJobs.ownerKey(OTHER)).isNotEqualTo(key);
    }

    private static ExecutorService direct() {
        return new ManualExecutor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }

    private static ChatResult await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return RESULT;
    }
}
