package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.ArrayList;
import java.util.List;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 학생이 확인 카드의 [확인]을 누른 뒤 (11/6).
 * <ul>
 *   <li><b>대화 맥락에 결과를 남긴다.</b> 확인은 모델을 거치지 않아서, 남기지 않으면 모델은 "예약할까요?"까지만 기억한다.
 *       그러면 "방금 잡은 거 취소해 줘"에 예약이 됐는지 모른 채 다시 조회하거나 안 됐다고 답한다. 실패(409 등)도 남겨
 *       "다시 해 줘"에 왜 안 됐는지 알고 다른 시간을 제안하게 한다</li>
 *   <li><b>빠른 답장을 붙인다.</b> 예약 뒤 "입실 번호 보여 줘", 취소 뒤 "다른 시간 찾아 줘"처럼 다음 한 수를 버튼으로</li>
 *   <li>입실 번호(accessCode)는 화면에만 간다. 맥락에는 action·예약 번호·문장만 넣고, 문장도 {@link PrivacyMask}로 거른다</li>
 * </ul>
 * 맥락 저장이 실패해도 확인 결과는 그대로 돌려준다(예약은 이미 됐으니 화면이 먼저다).
 */
@Service
public class ConfirmationFollowUp {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationFollowUp.class);
    /** 모델이 "학생이 화면에서 눌렀다"를 알도록 학생 차례로 남기는 글 */
    static final String PRESSED = "[화면] 확인 카드의 [확인]을 눌렀어요.";
    static final String FAILED_PREFIX = "확인을 처리하지 못했어요: ";

    private final ConversationMemory memory;
    private final AgentProperties props;
    private final MemoryProperties memoryProps;
    private final JsonMapper json;

    public ConfirmationFollowUp(ConversationMemory memory, AgentProperties props, MemoryProperties memoryProps, JsonMapper json) {
        this.memory = memory;
        this.props = props;
        this.memoryProps = memoryProps;
        this.json = json;
    }

    /**
     * @return 화면에 줄 본문. 성공이면 {@code suggestions}가 붙는다. 해석할 수 없는 본문은 그대로
     */
    public String record(UserHeaders user, ConfirmationGateway.Relayed relayed) {
        ObjectNode node = parse(relayed.json());
        if (node == null) {
            return relayed.json();
        }
        boolean ok = relayed.status() >= 200 && relayed.status() < 300;
        String message = node.path("message").asString(null);
        if (ok) {
            ArrayNode suggestions = node.putArray("suggestions");
            suggestions(node.path("action").asString("")).forEach(suggestions::add);
        }
        if (!user.isEmpty() && message != null && !message.isBlank()) {
            remember(MemberKey.of(user), ok ? done(node, message) : FAILED_PREFIX + message);
        }
        return json.writeValueAsString(node);
    }

    /** "예약했어요. (예약 7번, BOOKED)"처럼 모델이 뒤에 취소·연장할 때 쓸 번호를 같이 남긴다 */
    private static String done(ObjectNode node, String message) {
        long id = node.path("reservationId").asLong(0);
        String status = node.path("status").asString(null);
        StringBuilder sb = new StringBuilder(message);
        if (id > 0) {
            sb.append(" (예약 ").append(id).append("번");
            if (status != null && !status.isBlank()) {
                sb.append(", ").append(status);
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private void remember(String key, String outcome) {
        try {
            List<LlmMessage> conversation = new ArrayList<>(memory.load(key));
            conversation.add(new LlmMessage.User(PRESSED));
            conversation.add(new LlmMessage.Assistant(PrivacyMask.mask(outcome), List.of()));
            memory.save(key, AgentLoop.trim(conversation, props.maxHistoryTurns(), memoryProps.maxChars()));
        } catch (RuntimeException e) {
            log.warn("확인 결과를 대화 맥락에 남기지 못함: {}", e.getClass().getSimpleName());
        }
    }

    /** 확인 뒤 다음 한 수. 화면이 버튼으로 그리고 누르면 채팅으로 보낸다 (5.w와 같은 모양) */
    static List<String> suggestions(String action) {
        return switch (action) {
            case "CREATE" -> List.of("입실 번호 보여 줘", "내 예약 보여 줘");
            case "START_NOW" -> List.of("입실 번호 보여 줘", "연장할 수 있어?");
            case "CONTINUE" -> List.of("입실 번호 보여 줘", "내 예약 보여 줘");
            case "CANCEL" -> List.of("다른 시간 찾아 줘", "지금 빈 방 있어?");
            case "CHECKOUT" -> List.of("내 예약 보여 줘", "지금 빈 방 있어?");
            default -> List.of();
        };
    }

    private ObjectNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(body);
            return node instanceof ObjectNode o ? o : null;
        } catch (JacksonException e) {
            return null;
        }
    }
}
