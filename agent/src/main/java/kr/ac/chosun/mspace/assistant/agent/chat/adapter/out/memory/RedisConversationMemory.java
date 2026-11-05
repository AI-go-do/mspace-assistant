package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory;

import java.util.List;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.MemoryProperties;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 보관 (MEMORY_STORE=redis). 키 {@code agent:conv:<학생 키>}, 값은 {@link ConversationJson}, SET ... EX ttl.
 * <ul>
 *   <li>같은 학생은 {@link kr.ac.chosun.mspace.assistant.agent.chat.application.ChatJobs}가 한 번에 한 건만 받으니 덮어쓰기로 충분하다</li>
 *   <li>Redis가 죽어 있으면 읽기·저장은 기억 없이 넘어간다(경고 로그만). 지우기만은 실패를 알린다(지운 척하면 옛 대화가 되살아남)</li>
 *   <li>agent가 여러 대여도 같은 Redis를 보면 대화가 이어진다 (ChatJobs는 아직 메모리라 1대 기준)</li>
 * </ul>
 */
public class RedisConversationMemory implements ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationMemory.class);
    static final String PREFIX = "agent:conv:";

    private final StringRedisTemplate redis;
    private final MemoryProperties props;

    public RedisConversationMemory(StringRedisTemplate redis, MemoryProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public List<LlmMessage> load(String memberKey) {
        try {
            return ConversationJson.read(redis.opsForValue().get(PREFIX + memberKey));
        } catch (DataAccessException e) {
            log.warn("conversation memory load failed type={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public void save(String memberKey, List<LlmMessage> conversation) {
        try {
            redis.opsForValue().set(PREFIX + memberKey, ConversationJson.write(conversation), props.ttl());
        } catch (DataAccessException e) {
            log.warn("conversation memory save failed type={}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void clear(String memberKey) {
        try {
            redis.delete(PREFIX + memberKey);
        } catch (DataAccessException e) {
            log.warn("conversation memory clear failed type={}", e.getClass().getSimpleName());
            throw new Unavailable();
        }
    }
}
