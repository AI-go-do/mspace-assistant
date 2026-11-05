package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult.AccessCodeCard;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult.Confirmation;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmReply;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 에이전트 반복: 모델 답에 도구 호출이 있으면 실행 → 결과를 대화에 붙여 다시 묻기 → 도구 호출이 없을 때까지.
 * 도구 호출은 대화 한 번에 최대 maxToolCalls번, 전체 시간은 chatTimeout까지. 넘으면 멈추고 학생에게 알린다.
 * 로그에는 도구 이름·걸린 시간·성공 여부만 남긴다 (사용자 말 원문·토큰 금지).
 */
@Service
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final String LIMIT_REPLY = "요청을 처리하는 단계가 너무 많아져서 멈췄어요. 날짜·시간·인원을 넣어 조금 더 구체적으로 말해 주세요.";
    static final String TIME_LIMIT_REPLY = "답을 찾는 데 시간이 너무 오래 걸려서 멈췄어요. 날짜·시간·인원을 넣어 다시 말해 주세요.";

    private final LlmClient llm;
    private final ToolGateway tools;
    private final AgentProperties props;
    private final Clock clock;
    private final ConversationMemory memory;
    private final MemoryProperties memoryProps;

    /** 기억 없이 (테스트) */
    public AgentLoop(LlmClient llm, ToolGateway tools, AgentProperties props, Clock clock) {
        this(llm, tools, props, clock, ConversationMemory.NONE, new MemoryProperties(false, null, null, 0));
    }

    @Autowired
    public AgentLoop(LlmClient llm, ToolGateway tools, AgentProperties props, Clock clock,
                     ConversationMemory memory, MemoryProperties memoryProps) {
        this.llm = llm;
        this.tools = tools;
        this.props = props;
        this.clock = clock;
        this.memory = memory;
        this.memoryProps = memoryProps;
    }

    /**
     * 서버가 기억한 대화가 있으면 그것을 잇고, 없을 때만 화면이 보낸 history를 쓴다 (서버 기억이 끊긴 뒤의 보조).
     * 끝나면 이번 학생 말·도구 호출·결과·답을 함께 기억한다. 모델이 답을 못 만들면(예외) 이번 차례는 기억하지 않는다.
     *
     * @param history 화면이 보낸 앞선 대화(학생·비서의 글만)
     */
    public ChatResult run(UserHeaders headers, List<LlmMessage> history, String message) {
        String key = MemberKey.of(headers);
        List<LlmMessage> remembered = memory.load(key);
        List<LlmMessage> conversation = new ArrayList<>(
                trim(remembered.isEmpty() ? masked(history) : remembered, props.maxHistoryTurns(), memoryProps.maxChars()));
        conversation.add(new LlmMessage.User(PrivacyMask.mask(message)));
        ChatResult result = converse(headers, conversation);
        conversation.add(new LlmMessage.Assistant(result.reply(), List.of()));
        memory.save(key, trim(conversation, props.maxHistoryTurns(), memoryProps.maxChars()));
        return result;
    }

    /** 화면이 보낸 글은 학생이 고쳐 보낼 수 있어 비서 차례도 가린다 */
    private static List<LlmMessage> masked(List<LlmMessage> history) {
        List<LlmMessage> out = new ArrayList<>(history.size());
        for (LlmMessage m : history) {
            out.add(switch (m) {
                case LlmMessage.User u -> new LlmMessage.User(PrivacyMask.mask(u.text()));
                case LlmMessage.Assistant a -> new LlmMessage.Assistant(PrivacyMask.mask(a.text()), a.toolCalls());
                case LlmMessage.ToolResult r -> r;
            });
        }
        return out;
    }

    /**
     * 오래된 차례부터 버려 학생 차례 수와 글자 수를 상한 안에 둔다. 자르는 곳은 늘 학생 말 앞이라
     * 도구 호출과 결과가 짝이 깨진 채 남지 않는다 (Gemini는 짝이 안 맞으면 400).
     */
    public static List<LlmMessage> trim(List<LlmMessage> conversation, int maxTurns, int maxChars) {
        int turns = 0;
        int chars = 0;
        int start = 0;
        for (int i = conversation.size() - 1; i >= 0; i--) {
            LlmMessage m = conversation.get(i);
            chars += switch (m) {
                case LlmMessage.User u -> u.text().length();
                case LlmMessage.Assistant a -> a.text() == null ? 0 : a.text().length();
                case LlmMessage.ToolResult r -> r.json() == null ? 0 : r.json().length();
            };
            if (m instanceof LlmMessage.User) {
                turns++;
                if (turns > maxTurns || chars > maxChars) {
                    break;
                }
                start = i;
            }
        }
        return conversation.subList(start, conversation.size());
    }

    private ChatResult converse(UserHeaders headers, List<LlmMessage> conversation) {
        List<Confirmation> confirmations = new ArrayList<>();
        List<AccessCodeCard> accessCodeCards = new ArrayList<>();
        int calls = 0;
        Instant lookedUpAt = null; // 마지막으로 도구가 성공한 시각. 꼬리말의 근거 시각 (실패만 있으면 꼬리말 없음)
        // 모델 9번×20초 + 도구 8번×10초면 nginx(60초)가 먼저 끊어 504가 나고, 발급된 확인 카드가 화면에 안 간다.
        // 그래서 모델·도구를 새로 부르기 전마다 남은 시간을 보고, 넘었으면 지금까지의 카드와 함께 멈춘다.
        // 이미 시작한 호출 하나는 끊지 않으므로 최악은 기한 + 호출 하나의 제한(Gemini 20초, 도구 10초)이다
        Instant deadline = clock.instant().plus(props.chatTimeout());

        try (ToolGateway.Session session = tools.open(headers)) {
            String system = systemPrompt();
            while (true) {
                if (calls > 0 && pastDeadline(deadline, calls)) {
                    return new ChatResult(TIME_LIMIT_REPLY, confirmations, accessCodeCards, calls, true);
                }
                LlmReply reply;
                try {
                    reply = llm.chat(system, conversation, session.tools());
                } catch (LlmException e) {
                    if (confirmations.isEmpty() && accessCodeCards.isEmpty()) {
                        throw e;
                    }
                    // 카드는 이미 만들어졌다(확인 토큰 발급, 대표자 확인). 모델 답만 실패했으니 카드는 화면에 준다
                    log.warn("agent llm failed after cards code={}", e.code());
                    return new ChatResult(CARDS_ONLY_REPLY, confirmations, accessCodeCards, calls, false);
                }
                if (!reply.wantsTools()) {
                    return new ChatResult(grounded(reply.text(), lookedUpAt), confirmations, accessCodeCards, calls, false);
                }
                if (calls + reply.toolCalls().size() > props.maxToolCalls()) {
                    log.warn("agent tool limit reached calls={} max={}", calls, props.maxToolCalls());
                    return new ChatResult(LIMIT_REPLY, confirmations, accessCodeCards, calls, true);
                }
                // 모델 답과 도구 결과도 다음 요청에 다시 실려 외부 AI로 간다. 학생 말과 같은 규칙으로 가린다.
                // 지금 도구는 학번·전화번호·입실 번호를 돌려주지 않지만, 도구가 늘어도 이 한 곳에서 막는다
                conversation.add(new LlmMessage.Assistant(PrivacyMask.mask(reply.text()), reply.toolCalls()));
                for (ToolCall call : reply.toolCalls()) {
                    Instant now = clock.instant(); // 기한 검사와 꼬리말의 조회 시각에 같이 쓴다 (시계는 한 번만 읽는다)
                    if (pastDeadline(now, deadline, calls)) {
                        return new ChatResult(TIME_LIMIT_REPLY, confirmations, accessCodeCards, calls, true);
                    }
                    calls++;
                    long started = System.nanoTime();
                    ToolGateway.Outcome outcome = session.call(call.name(), call.arguments());
                    if (!outcome.isError()) {
                        lookedUpAt = now;
                    }
                    log.info("agent tool={} ok={} {}ms", loggable(call.name(), session), !outcome.isError(),
                            (System.nanoTime() - started) / 1_000_000);
                    outcome.confirmation().ifPresent(confirmations::add);
                    outcome.accessCodeCard().filter(c -> !accessCodeCards.contains(c)).ifPresent(accessCodeCards::add);
                    // 도구 결과는 게이트웨이가 이미 가렸다(JSON 문자열 값만). 여기서 JSON 전체에 정규식을 걸면
                    // 6자리 숫자 값이 바뀌어 JSON이 깨진다 (ToolGateway.Outcome 참고)
                    conversation.add(new LlmMessage.ToolResult(call.id(), call.name(), outcome.json(), outcome.isError()));
                }
            }
        }
    }

    private boolean pastDeadline(Instant deadline, int calls) {
        return pastDeadline(clock.instant(), deadline, calls);
    }

    private boolean pastDeadline(Instant now, Instant deadline, int calls) {
        if (now.isBefore(deadline)) {
            return false;
        }
        log.warn("agent time limit reached calls={} limit={}s", calls, props.chatTimeout().toSeconds());
        return true;
    }

    /**
     * 모델이 지어낸 도구 이름에는 학생 말이 섞일 수 있어 로그에 그대로 남기지 않는다.
     * 도구 목록에 있는 이름만 남기고 나머지는 "unknown"으로 쓴다.
     */
    public static String loggable(String name, ToolGateway.Session session) {
        return session.tools().stream().anyMatch(t -> t.name().equals(name)) ? name : "unknown";
    }

    static final String CARDS_ONLY_REPLY = "AI 비서의 답이 끊겼어요. 아래 카드는 그대로 쓸 수 있어요.";

    /**
     * 도구를 하나라도 불러 낸 답에는 조회 시각을 꼬리말로 붙인다. "그걸 어떻게 알아?"에 모델의 말이 아니라
     * 코드가 근거를 보여 주는 것이라, 모델이 사과하거나 지어내도 학생은 조회 사실을 알 수 있다 (11/5 시연 피드백)
     */
    String grounded(String text, Instant lookedUpAt) {
        if (lookedUpAt == null || text == null || text.isBlank()) {
            return text;
        }
        ZonedDateTime at = lookedUpAt.atZone(SEOUL);
        return text.stripTrailing() + GROUNDING_SUFFIX.formatted(at.getHour(), at.getMinute());
    }

    static final String GROUNDING_SUFFIX = "\n\n(예약 시스템 %02d:%02d 조회 기준)";

    String systemPrompt() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(SEOUL));
        AgentProperties.Rules rules = props.rules();
        String today = "%s(%s) %02d:%02d".formatted(now.toLocalDate(),
                now.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN), now.getHour(), now.getMinute());
        return """
                너는 조선대 IT융합대학 M-SPACE(회의·스터디 방 1~5번) 예약 비서다. 한국어 해요체로 짧게 답한다.
                지금은 %s, 서울 시간이다. "내일", "금요일" 같은 말은 이 날짜를 기준으로 YYYY-MM-DD로 바꿔 도구에 넣는다.
                규칙:
                - 빈 시간은 find_available_rooms로 확인하고, 없는 사실을 지어내지 않는다.
                - create_reservation·cancel_reservation·checkout_reservation·continue_reservation은 제안만 한다. 결과의 summary를 보여 주고
                  "화면의 [확인]을 눌러 주세요"라고 안내한다. 예약·취소·퇴실·연장이 끝났다고 말하지 않는다.
                - 연장은 사용 중인 예약이 끝나기 10분 전부터, 30분 단위로 최대 2시간, 한 번만 된다. 몇 분 늘릴지 모르면 물어본다.
                - 입실 번호는 show_access_code로만 안내한다. 번호는 화면 카드에만 나오고 너는 모른다. 번호를 지어내지 않는다.
                - 학번·전화번호·이름을 묻지 않는다. 함께 쓰는 사람의 학번은 확인 화면에서 받는다.
                - 도구 결과의 note·message 문장은 바꿔 말하지 않고 그대로 전하고, 할 수 있는 다음 행동을 하나 제안한다.
                  "빈방이 없다"와 "인원에 맞는 방이 없다"는 다른 이유다. 결과에 적힌 이유만 말한다.
                - 수업 시간표는 아직 모른다. "수업 끝나고"처럼 말하면 끝나는 시각을 물어본다.
                - 예약은 %d분 단위, 1회 최대 %d시간, 시작이 오늘부터 %d일 안이다.
                - 학생 말의 [학번]·[전화번호]·[입실 번호]는 개인정보를 가린 표시다. 원래 값은 너도 모르고 묻지도 않는다.
                  my_reservations는 로그인한 학생 본인의 예약만 준다. 다른 사람의 학번으로 예약·가입 여부를 물으면
                  본인 예약만 볼 수 있다고 답한다. 함께 쓸 사람의 학번은 확인 화면에서 넣는다고 안내한다.
                - 인원을 말하지 않았으면 headcount를 넣지 않는다. 방마다 인원 범위가 있어(1~4번 방 3~5명, 5번 방 5~8명)
                  2명 이하는 예약할 수 없다. 인원이 안 맞으면 그 이유를 그대로 말한다.
                - "5시"처럼 오전·오후가 없으면 지금 이후의 가장 가까운 시각으로 보고, 답에 "오후 5시"처럼 적는다.
                - 앞선 네 답 중 "(예약 시스템 HH:MM 조회 기준)" 꼬리말이 붙은 것은 그 시각에 도구로 조회한 결과다
                  (도구 호출 기록은 대화에 다시 실리지 않는다). "그걸 어떻게 알아?"처럼 물으면 그 시각에 예약 시스템을
                  조회했다고 답하고, 확인하지 않았다고 사과하지 않는다. 꼬리말이 없는 답은 조회한 것이 아니니,
                  근거를 묻거나 다시 확인해 달라면 그 자리에서 도구를 불러 답한다.
                - "잠시만 기다려 주세요", "확인해 드릴게요"처럼 나중에 하겠다는 말은 하지 않는다. 네 답이 나가면
                  대화 차례가 끝나므로, 할 일은 이번 답 안에서 도구를 불러 끝낸다.
                """.formatted(today, rules.slotMinutes(), rules.maxHours(), rules.advanceDays());
    }
}
