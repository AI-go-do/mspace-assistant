package kr.ac.chosun.mspace.assistant.mcp.confirm.adapter.out.memory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ConfirmationRepository;
import org.springframework.stereotype.Component;

/**
 * 서버 한 대용 메모리 저장소. 서버를 다시 켜면 대기 중인 확인이 사라진다.
 * {@code ConcurrentHashMap.remove(key, value)}가 원자적이라 두 번 클릭해도 하나만 꺼낸다.
 */
@Component
public class InMemoryConfirmationRepository implements ConfirmationRepository {

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Used> used = new ConcurrentHashMap<>();

    @Override
    public void save(String token, Pending value) {
        pending.put(token, value);
    }

    @Override
    public Optional<Pending> find(String token) {
        return Optional.ofNullable(pending.get(token));
    }

    @Override
    public boolean removeIfSame(String token, Pending expected) {
        return pending.remove(token, expected);
    }

    @Override
    public void markUsed(String token, Used value) {
        used.put(token, value);
    }

    @Override
    public Optional<Used> findUsed(String token) {
        return Optional.ofNullable(used.get(token));
    }

    @Override
    public void purgeExpired(Instant now) {
        pending.values().removeIf(p -> !now.isBefore(p.expiresAt()));
        used.values().removeIf(u -> !now.isBefore(u.until()));
    }
}
