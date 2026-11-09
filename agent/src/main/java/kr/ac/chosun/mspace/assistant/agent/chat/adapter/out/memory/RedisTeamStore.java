package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory;

import java.util.List;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.Team;
import kr.ac.chosun.mspace.assistant.agent.chat.application.TeamStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Redis 보관 (MEMORY_STORE=redis). 키 {@code agent:teams:<학생 키>}, 값은 팀 목록 JSON. TTL 없음 */
public class RedisTeamStore implements TeamStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTeamStore.class);
    static final String PREFIX = "agent:teams:";
    private static final TypeReference<List<Team>> LIST = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();

    public RedisTeamStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<Team> load(String memberKey) {
        try {
            String raw = redis.opsForValue().get(PREFIX + memberKey);
            return raw == null ? List.of() : List.copyOf(json.readValue(raw, LIST));
        } catch (DataAccessException | JacksonException e) {
            log.warn("teams load failed type={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public void save(String memberKey, List<Team> teams) {
        try {
            redis.opsForValue().set(PREFIX + memberKey, json.writeValueAsString(teams));
        } catch (DataAccessException e) {
            log.warn("teams save failed type={}", e.getClass().getSimpleName());
            throw new ConversationMemory.Unavailable();
        }
    }
}
