package kr.ac.chosun.mspace.assistant.mcp.confirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ConfirmProperties;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ConfirmationStore;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.PendingAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 확인 토큰: 한 번만, 짧게 만료, 본인만 (docs/설계.md 2.3, 이슈 #12) */
class ConfirmationStoreTest {

    static final UserCredential ME = new UserCredential(Map.of("X-User-Id", "1"));
    static final UserCredential OTHER = new UserCredential(Map.of("X-User-Id", "2"));
    static final PendingAction ACTION = new PendingAction.Create(1,
            OffsetDateTime.parse("2026-11-03T14:00:00+09:00"), OffsetDateTime.parse("2026-11-03T16:00:00+09:00"), 3);

    /** 테스트에서 시간을 넘길 수 있는 시계 */
    static class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-03T04:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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

    final MovableClock clock = new MovableClock();
    final ConfirmationStore store = new ConfirmationStore(clock, new ConfirmProperties(Duration.ofMinutes(2)));

    @Test
    @DisplayName("토큰은 한 번만 꺼낼 수 있다. 두 번째는 '이미 처리한 확인'")
    void oneTimeUse() {
        // given
        String token = store.issue(ME, ACTION).token();

        // when
        PendingAction first = store.take(token, ME);

        // then
        assertThat(first).isEqualTo(ACTION);
        assertThatThrownBy(() -> store.take(token, ME))
                .isInstanceOf(CoreApiException.class).extracting("code").isEqualTo("CONFIRMATION_USED");
    }

    @Test
    @DisplayName("2분이 지나면 만료. 만료된 토큰은 실행되지 않는다")
    void expires() {
        String token = store.issue(ME, ACTION).token();

        clock.now = clock.now.plus(Duration.ofMinutes(2));

        assertThatThrownBy(() -> store.take(token, ME))
                .isInstanceOf(CoreApiException.class).extracting("code").isEqualTo("CONFIRMATION_EXPIRED");
    }

    @Test
    @DisplayName("다른 사람이 토큰을 쓰면 '찾을 수 없음'이고, 본인 토큰은 그대로 남는다")
    void otherUser() {
        String token = store.issue(ME, ACTION).token();

        assertThatThrownBy(() -> store.take(token, OTHER))
                .isInstanceOf(CoreApiException.class).extracting("code").isEqualTo("CONFIRMATION_NOT_FOUND");
        assertThat(store.take(token, ME)).isEqualTo(ACTION);
    }

    @Test
    @DisplayName("없는 토큰은 '찾을 수 없음'")
    void unknown() {
        assertThatThrownBy(() -> store.take("c_nope", ME))
                .isInstanceOf(CoreApiException.class).extracting("code").isEqualTo("CONFIRMATION_NOT_FOUND");
    }

    @Test
    @DisplayName("같은 토큰으로 동시에 32번 눌러도 성공은 1번, 나머지는 모두 CONFIRMATION_USED")
    void concurrentDoubleClick() throws Exception {
        // given
        String token = store.issue(ME, ACTION).token();
        int threads = 32;
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var success = new AtomicInteger();
        var used = new AtomicInteger();

        // when
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        store.take(token, ME);
                        success.incrementAndGet();
                    } catch (CoreApiException e) {
                        if ("CONFIRMATION_USED".equals(e.code())) {
                            used.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            go.countDown();
            done.await();
        }

        // then
        assertThat(success.get()).isEqualTo(1);
        assertThat(used.get()).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("peek은 꺼내지 않는다. 토큰은 core 멱등 키 최대 64자 안")
    void peekAndLength() {
        String token = store.issue(ME, ACTION).token();

        assertThat(store.peek(token, ME)).isEqualTo(ACTION);
        assertThat(store.take(token, ME)).isEqualTo(ACTION);
        assertThat(token).startsWith("c_").hasSizeLessThanOrEqualTo(64);
    }
}
