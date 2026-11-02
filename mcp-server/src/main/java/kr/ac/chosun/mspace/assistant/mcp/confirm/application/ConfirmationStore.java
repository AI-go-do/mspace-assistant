package kr.ac.chosun.mspace.assistant.mcp.confirm.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import org.springframework.stereotype.Component;

/**
 * 확인 토큰 저장소 (docs/설계.md 2.3). 토큰은 한 번만 꺼낼 수 있고 짧게 만료된다.
 * <ul>
 *   <li>두 번 클릭(동시에 2개): {@code map.remove(token, entry)}는 원자적이라 하나만 성공한다</li>
 *   <li>다른 사람 토큰: 사용자 지문이 다르면 꺼내지 않고 "찾을 수 없음" (있다는 사실도 숨김)</li>
 *   <li>사용자 지문은 헤더 값의 SHA-256. 원문 토큰(JWT 등)은 저장하지 않는다</li>
 * </ul>
 * 메모리 저장이라 서버를 다시 켜면 대기 중인 확인이 사라진다. 서버 2대 이상이면 Redis 등으로 바꾼다.
 */
@Component
public class ConfirmationStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> pending = new ConcurrentHashMap<>();
    /** 이미 쓴 토큰 → 기억할 시각까지. "이미 처리했어요"와 "찾을 수 없어요"를 구분하려고 둔다 */
    private final Map<String, Instant> used = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ConfirmProperties props;

    public ConfirmationStore(Clock clock, ConfirmProperties props) {
        this.clock = clock;
        this.props = props;
    }

    public Issued issue(UserCredential credential, PendingAction action) {
        purgeExpired();
        // c_ + 32바이트 난수(43자) = 45자. core 멱등 키 최대 64자 안
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = "c_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = clock.instant().plus(props.ttl());
        pending.put(token, new Entry(fingerprint(credential), action, expiresAt));
        return new Issued(token, expiresAt);
    }

    /** 꺼내지 않고 내용만 본다 (실행 전 입력 검사용). 검사 규칙은 take와 같다 */
    public PendingAction peek(String token, UserCredential credential) {
        return check(token, credential).action();
    }

    /** 토큰을 꺼낸다. 꺼낸 순간 지워져서 두 번째 호출은 CONFIRMATION_USED */
    public PendingAction take(String token, UserCredential credential) {
        Entry entry = check(token, credential);
        if (!pending.remove(token, entry)) {
            throw alreadyUsed();
        }
        used.put(token, clock.instant().plus(props.ttl().multipliedBy(5)));
        return entry.action();
    }

    private Entry check(String token, UserCredential credential) {
        Entry entry = token == null ? null : pending.get(token);
        if (entry == null) {
            throw used.containsKey(token == null ? "" : token) ? alreadyUsed() : notFound();
        }
        if (!entry.fingerprint().equals(fingerprint(credential))) {
            throw notFound();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            pending.remove(token, entry);
            throw new CoreApiException("CONFIRMATION_EXPIRED", "확인 시간이 지났어요. 다시 말해 주세요.", 410);
        }
        return entry;
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        pending.values().removeIf(e -> !now.isBefore(e.expiresAt()));
        used.values().removeIf(until -> !now.isBefore(until));
    }

    static String fingerprint(UserCredential credential) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(credential.headers()).forEach((name, value) -> {
                digest.update(name.toLowerCase().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CoreApiException notFound() {
        return new CoreApiException("CONFIRMATION_NOT_FOUND", "찾을 수 없는 확인이에요. 다시 말해 주세요.", 404);
    }

    private static CoreApiException alreadyUsed() {
        return new CoreApiException("CONFIRMATION_USED", "이미 처리한 확인이에요.", 409);
    }

    public record Issued(String token, Instant expiresAt) {
    }

    private record Entry(String fingerprint, PendingAction action, Instant expiresAt) {
    }
}
