package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 채팅 접수 → 결과 조회(폴링). 5차 회의(멘토) 권장.
 * <p>
 * 접수는 바로 돌아가고, 에이전트 반복은 별도 스레드에서 돈다. 화면은 jobId로 결과를 조회한다.
 * 요청 하나가 짧아져 nginx 제한 시간(60초)과 상관없이 45초 기한 끝까지 카드를 돌려줄 수 있다.
 * <ul>
 *   <li>결과는 접수한 학생만 본다: 인증 헤더 값의 SHA-256을 함께 저장하고 조회 때 비교한다 (값 자체는 저장하지 않음)</li>
 *   <li>학생 한 명은 동시에 한 건만: 앞 접수가 끝나기 전에 또 보내면 CHAT_IN_PROGRESS</li>
 *   <li>메모리 보관이라 agent 인스턴스가 하나일 때만 맞다. 여러 대로 늘리면 Redis·DB로 옮겨야 한다</li>
 * </ul>
 */
public class ChatJobs implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatJobs.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Status { PENDING, DONE, FAILED }

    public record Failure(String code, String message) {
    }

    /** 조회 결과. result는 DONE일 때만, error는 FAILED일 때만 */
    public record View(String jobId, Status status, ChatResult result, Failure error) {
    }

    public sealed interface Submitted {
        record Accepted(String jobId) implements Submitted {
        }

        /** code: CHAT_IN_PROGRESS(같은 학생의 앞 접수 진행 중) | AGENT_BUSY(서버 대기열이 가득 참) */
        record Rejected(String code, String message) implements Submitted {
        }
    }

    /** startedAt: 작업 스레드가 집어 든 시각. 돌기 시작한 작업은 pendingTtl로 지우지 않는다 (AgentLoop 기한이 끝을 보장) */
    private record Job(String owner, Instant createdAt, AtomicReference<Instant> startedAt, AtomicReference<View> view,
                       AtomicReference<Instant> doneAt) {
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> runningByOwner = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final ChatJobProperties props;
    private final Clock clock;

    public ChatJobs(ChatJobProperties props, Clock clock) {
        this(props, clock, newExecutor(props));
    }

    /** 테스트는 바로 실행하는 executor를 넣는다 */
    public ChatJobs(ChatJobProperties props, Clock clock, ExecutorService executor) {
        this.props = props;
        this.clock = clock;
        this.executor = executor;
    }

    private static ExecutorService newExecutor(ChatJobProperties props) {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(props.workers(), props.workers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(props.queueCapacity()),
                r -> {
                    Thread t = new Thread(r, "agent-chat-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public Submitted submit(UserHeaders user, Supplier<ChatResult> work) {
        sweep();
        String owner = ownerKey(user);
        String jobId = newId();
        // putIfAbsent로 검사와 등록을 한 번에: 같은 학생이 동시에 두 번 보내도 한 건만 통과한다
        String running = runningByOwner.putIfAbsent(owner, jobId);
        if (running != null) {
            return new Submitted.Rejected("CHAT_IN_PROGRESS", "앞의 질문에 아직 답하는 중이에요. 답이 온 뒤에 다시 보내 주세요.");
        }
        Job job = new Job(owner, clock.instant(), new AtomicReference<>(),
                new AtomicReference<>(new View(jobId, Status.PENDING, null, null)), new AtomicReference<>());
        jobs.put(jobId, job);
        try {
            executor.execute(() -> run(jobId, job, work));
        } catch (RejectedExecutionException e) {
            jobs.remove(jobId);
            runningByOwner.remove(owner, jobId);
            log.warn("agent chat queue full workers={} queue={}", props.workers(), props.queueCapacity());
            return new Submitted.Rejected("AGENT_BUSY", "지금 비서를 쓰는 사람이 많아요. 잠시 뒤 다시 보내 주세요.");
        }
        return new Submitted.Accepted(jobId);
    }

    /** 없는 jobId, 남의 jobId, 보관 시간이 지난 jobId는 모두 같은 empty (있는지조차 알려 주지 않는다) */
    public Optional<View> find(String jobId, UserHeaders user) {
        sweep();
        Job job = jobId == null ? null : jobs.get(jobId);
        if (job == null || !MessageDigest.isEqual(
                job.owner().getBytes(StandardCharsets.US_ASCII), ownerKey(user).getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        return Optional.of(job.view().get());
    }

    /** sweep()이 시작 전 접수를 만료시켰다는 표시. 실제 시각이 아니다 */
    private static final Instant EXPIRED = Instant.EPOCH;

    private void run(String jobId, Job job, Supplier<ChatResult> work) {
        // 대기열에서 pendingTtl을 넘겨 이미 지워진 접수는 돌리지 않는다 (아무도 결과를 못 받는데 모델만 부르게 됨)
        if (jobs.get(jobId) != job || !job.startedAt().compareAndSet(null, clock.instant())) {
            return;
        }
        View done = new View(jobId, Status.FAILED, null,
                new Failure("AGENT_FAILED", "답을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요."));
        try {
            done = new View(jobId, Status.DONE, work.get(), null);
        } catch (LlmException e) {
            done = new View(jobId, Status.FAILED, null, new Failure(e.code(), e.getMessage()));
        } catch (ToolGateway.Unavailable e) {
            done = new View(jobId, Status.FAILED, null, new Failure("TOOLS_UNAVAILABLE", e.getMessage()));
        } catch (RuntimeException e) {
            // 메시지에는 학생 말·토큰이 섞일 수 있어 종류만 남긴다 (CLAUDE.md 4장)
            log.warn("agent chat failed type={}", e.getClass().getSimpleName());
        } finally {
            // Error(OutOfMemoryError 등)가 나도 PENDING으로 남아 그 학생이 계속 CHAT_IN_PROGRESS가 되지 않게
            job.view().set(done);
            job.doneAt().set(clock.instant());
            runningByOwner.remove(job.owner(), jobId);
        }
    }

    /** 끝난 지 resultTtl이 지난 결과, 접수한 지 pendingTtl이 지나도록 시작하지 못한 접수를 지운다. 보관 건수가 적어 매번 훑어도 된다 */
    private void sweep() {
        Instant now = clock.instant();
        jobs.entrySet().removeIf(e -> {
            Job job = e.getValue();
            Instant doneAt = job.doneAt().get();
            // 시작 못 한 접수는 startedAt을 EXPIRED로 바꾸는 데 성공했을 때만 지운다.
            // run()도 같은 칸을 compareAndSet으로 잡으므로 "시작"과 "만료" 중 하나만 이긴다 (그 사이에 시작된 작업을 지우지 않게)
            boolean expired = doneAt != null
                    ? doneAt.plus(props.resultTtl()).isBefore(now)
                    : job.createdAt().plus(props.pendingTtl()).isBefore(now)
                            && job.startedAt().compareAndSet(null, EXPIRED);
            if (expired) {
                runningByOwner.remove(job.owner(), e.getKey());
            }
            return expired;
        });
    }

    int size() {
        return jobs.size();
    }

    private static String newId() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "j_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 헤더 이름·값을 정렬해 SHA-256. 원래 값(토큰)은 메모리에 따로 남기지 않는다 */
    static String ownerKey(UserHeaders user) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> e : new TreeMap<>(user.values()).entrySet()) {
                digest.update(e.getKey().toLowerCase().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
