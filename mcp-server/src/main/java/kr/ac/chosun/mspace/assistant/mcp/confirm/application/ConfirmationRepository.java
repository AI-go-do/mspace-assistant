package kr.ac.chosun.mspace.assistant.mcp.confirm.application;

import java.time.Instant;
import java.util.Optional;

/**
 * 확인 토큰을 담아 두는 곳 (포트). 규칙(한 번만 꺼내기, 주인 지문 비교, 만료)은 {@link ConfirmationStore}가 갖고,
 * 여기는 담기·꺼내기만 한다. 지금은 메모리 어댑터, 서버가 2대 이상이 되면 Redis 어댑터로 바꾼다.
 */
public interface ConfirmationRepository {

    void save(String token, Pending pending);

    Optional<Pending> find(String token);

    /** 저장된 값이 {@code expected}와 같을 때만 지운다. 원자적이어야 한다: 동시에 두 번 불러도 하나만 true */
    boolean removeIfSame(String token, Pending expected);

    void markUsed(String token, Used used);

    Optional<Used> findUsed(String token);

    /** 만료된 대기·사용 기록을 지운다 */
    void purgeExpired(Instant now);

    record Pending(String fingerprint, PendingAction action, Instant expiresAt) {
    }

    record Used(String fingerprint, Instant until) {
    }
}
