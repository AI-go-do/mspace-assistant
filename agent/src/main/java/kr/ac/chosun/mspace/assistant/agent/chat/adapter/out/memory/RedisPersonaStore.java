package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory;

import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.Persona;
import kr.ac.chosun.mspace.assistant.agent.chat.application.PersonaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis 보관 (MEMORY_STORE=redis). 키 {@code agent:persona:<학생 키>}, 값은 성향 이름. TTL 없음 */
public class RedisPersonaStore implements PersonaStore {

    private static final Logger log = LoggerFactory.getLogger(RedisPersonaStore.class);
    static final String PREFIX = "agent:persona:";

    private final StringRedisTemplate redis;

    public RedisPersonaStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Persona load(String memberKey) {
        try {
            return Persona.parse(redis.opsForValue().get(PREFIX + memberKey)).orElse(Persona.DEFAULT);
        } catch (DataAccessException e) {
            log.warn("persona load failed type={}", e.getClass().getSimpleName());
            return Persona.DEFAULT;
        }
    }

    @Override
    public void save(String memberKey, Persona persona) {
        try {
            redis.opsForValue().set(PREFIX + memberKey, persona.name());
        } catch (DataAccessException e) {
            log.warn("persona save failed type={}", e.getClass().getSimpleName());
            throw new ConversationMemory.Unavailable();
        }
    }
}
