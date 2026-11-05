package kr.ac.chosun.mspace.assistant.mcp.schedule.adapter.out.redis;

import java.util.Optional;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.ScheduleRepository;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.Timetable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis 보관 (SCHEDULE_STORE=redis). 키 {@code agent:schedule:<학생 키>}, 값은 Timetable JSON, TTL 없음
 * (학기 내내 쓰고, 학생이 지우거나 다시 넣는다).
 * <ul>
 *   <li>대화 기억(agent:conv:)과 같은 Redis, 같은 학생 키 규칙. 접두어가 달라 서로 덮지 않는다</li>
 *   <li>Redis가 죽어 있으면 읽기도 {@link Unavailable}: 없는 척하면 "시간표를 먼저 넣어 주세요"로 잘못 안내한다</li>
 *   <li>값이 깨져 있으면(옛 모양 등) 없는 것으로 보고 경고 로그만 남긴다. 학생이 다시 넣으면 고쳐진다</li>
 * </ul>
 */
public class RedisScheduleRepository implements ScheduleRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisScheduleRepository.class);
    public static final String PREFIX = "agent:schedule:";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringRedisTemplate redis;

    public RedisScheduleRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Timetable> find(String studentKey) {
        String json;
        try {
            json = redis.opsForValue().get(PREFIX + studentKey);
        } catch (DataAccessException e) {
            log.warn("schedule load failed type={}", e.getClass().getSimpleName());
            throw new Unavailable();
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            Timetable t = JSON.readValue(json, Timetable.class);
            // Jackson은 읽을 때 OffsetDateTime을 UTC로 바꾼다 (트러블슈팅 TS-01)
            return Optional.of(new Timetable(t.classes(), SeoulTime.of(t.updatedAt())));
        } catch (JacksonException | NullPointerException e) {
            log.warn("schedule value unreadable type={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void save(String studentKey, Timetable timetable) {
        try {
            redis.opsForValue().set(PREFIX + studentKey, JSON.writeValueAsString(timetable));
        } catch (DataAccessException e) {
            log.warn("schedule save failed type={}", e.getClass().getSimpleName());
            throw new Unavailable();
        }
    }

    @Override
    public void delete(String studentKey) {
        try {
            redis.delete(PREFIX + studentKey);
        } catch (DataAccessException e) {
            log.warn("schedule delete failed type={}", e.getClass().getSimpleName());
            throw new Unavailable();
        }
    }
}
