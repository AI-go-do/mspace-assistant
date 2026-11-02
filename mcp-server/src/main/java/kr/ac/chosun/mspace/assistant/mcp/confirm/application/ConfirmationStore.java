package kr.ac.chosun.mspace.assistant.mcp.confirm.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.TreeMap;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ConfirmationRepository.Pending;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ConfirmationRepository.Used;
import org.springframework.stereotype.Component;

/**
 * 확인 토큰 규칙 (docs/설계.md 2.3). 토큰은 한 번만 꺼낼 수 있고 짧게 만료된다.
 * 담아 두는 곳은 {@link ConfirmationRepository} 포트라서, 저장소를 바꿔도 이 규칙과 테스트는 그대로다.
 * <ul>
 *   <li>두 번 클릭(동시에 2개): {@code removeIfSame}이 원자적이라 하나만 성공한다</li>
 *   <li>다른 사람 토큰: 사용자 지문이 다르면 꺼내지 않고 "찾을 수 없음" (있다는 사실도 숨김)</li>
 *   <li>사용자 지문은 헤더 값의 SHA-256. 원문 토큰(JWT 등)은 저장하지 않는다</li>
 * </ul>
 */
@Component
public class ConfirmationStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 대기 중인 토큰, 그리고 이미 쓴 토큰(주인에게만 "이미 처리했어요", 남에게는 "찾을 수 없어요") */
    private final ConfirmationRepository repository;
    private final Clock clock;
    private final ConfirmProperties props;

    public ConfirmationStore(ConfirmationRepository repository, Clock clock, ConfirmProperties props) {
        this.repository = repository;
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
        repository.save(token, new Pending(fingerprint(credential), action, expiresAt));
        return new Issued(token, expiresAt);
    }

    /** 꺼내지 않고 내용만 본다 (실행 전 입력 검사용). 검사 규칙은 take와 같다 */
    public PendingAction peek(String token, UserCredential credential) {
        return check(token, credential).action();
    }

    /** 토큰을 꺼낸다. 꺼낸 순간 지워져서 두 번째 호출은 CONFIRMATION_USED */
    public PendingAction take(String token, UserCredential credential) {
        Pending entry = check(token, credential);
        // 지우기 전에 사용 기록부터 남긴다. 반대 순서면 동시에 누른 다른 요청이 그 틈에
        // "없음"(CONFIRMATION_NOT_FOUND)을 받는다. 기록만 남고 지우기에 지면 아래에서 USED로 끝난다
        repository.markUsed(token, new Used(entry.fingerprint(), clock.instant().plus(props.ttl().multipliedBy(5))));
        if (!repository.removeIfSame(token, entry)) {
            throw alreadyUsed();
        }
        return entry.action();
    }

    private Pending check(String token, UserCredential credential) {
        String me = fingerprint(credential);
        Pending entry = token == null ? null : repository.find(token).orElse(null);
        if (entry == null) {
            Used u = token == null ? null : repository.findUsed(token).orElse(null);
            // 다른 학생에게는 토큰이 쓰였다는 사실도 숨긴다 (설계.md 2.3)
            throw u != null && u.fingerprint().equals(me) ? alreadyUsed() : notFound();
        }
        if (!entry.fingerprint().equals(me)) {
            throw notFound();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            repository.removeIfSame(token, entry);
            throw new CoreApiException("CONFIRMATION_EXPIRED", "확인 시간이 지났어요. 다시 말해 주세요.", 410);
        }
        return entry;
    }

    private void purgeExpired() {
        repository.purgeExpired(clock.instant());
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
}
