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

    public AgentLoop(LlmClient llm, ToolGateway tools, AgentProperties props, Clock clock) {
        this.llm = llm;
        this.tools = tools;
        this.props = props;
        this.clock = clock;
    }

    /**
     * @param history 앞선 대화(학생·비서의 글만). 도구 결과는 다시 보내지 않는다
     */
    public ChatResult run(UserHeaders headers, List<LlmMessage> history, String message) {
        List<LlmMessage> conversation = new ArrayList<>();
        for (LlmMessage m : history) {
            // 비서 차례도 클라이언트가 보낸 글이라 가린다. 학생이 앞 대화를 고쳐 보낼 수 있다
            conversation.add(switch (m) {
                case LlmMessage.User u -> new LlmMessage.User(PrivacyMask.mask(u.text()));
                case LlmMessage.Assistant a -> new LlmMessage.Assistant(PrivacyMask.mask(a.text()), a.toolCalls());
                case LlmMessage.ToolResult r -> r;
            });
        }
        conversation.add(new LlmMessage.User(PrivacyMask.mask(message)));
        List<Confirmation> confirmations = new ArrayList<>();
        List<AccessCodeCard> accessCodeCards = new ArrayList<>();
        int calls = 0;
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
                    return new ChatResult(reply.text(), confirmations, accessCodeCards, calls, false);
                }
                if (calls + reply.toolCalls().size() > props.maxToolCalls()) {
                    log.warn("agent tool limit reached calls={} max={}", calls, props.maxToolCalls());
                    return new ChatResult(LIMIT_REPLY, confirmations, accessCodeCards, calls, true);
                }
                // 모델 답과 도구 결과도 다음 요청에 다시 실려 외부 AI로 간다. 학생 말과 같은 규칙으로 가린다.
                // 지금 도구는 학번·전화번호·입실 번호를 돌려주지 않지만, 도구가 늘어도 이 한 곳에서 막는다
                conversation.add(new LlmMessage.Assistant(PrivacyMask.mask(reply.text()), reply.toolCalls()));
                for (ToolCall call : reply.toolCalls()) {
                    if (pastDeadline(deadline, calls)) {
                        return new ChatResult(TIME_LIMIT_REPLY, confirmations, accessCodeCards, calls, true);
                    }
                    calls++;
                    long started = System.nanoTime();
                    ToolGateway.Outcome outcome = session.call(call.name(), call.arguments());
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
        if (clock.instant().isBefore(deadline)) {
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
                - create_reservation·cancel_reservation·checkout_reservation은 제안만 한다. 결과의 summary를 보여 주고
                  "화면의 [확인]을 눌러 주세요"라고 안내한다. 예약·취소·퇴실이 끝났다고 말하지 않는다.
                - 입실 번호는 show_access_code로만 안내한다. 번호는 화면 카드에만 나오고 너는 모른다. 번호를 지어내지 않는다.
                - 학번·전화번호·이름을 묻지 않는다. 함께 쓰는 사람의 학번은 확인 화면에서 받는다.
                - 도구가 오류를 주면 그 문장을 학생에게 전하고, 할 수 있는 다음 행동을 하나 제안한다.
                - 수업 시간표는 아직 모른다. "수업 끝나고"처럼 말하면 끝나는 시각을 물어본다.
                - 예약은 %d분 단위, 1회 최대 %d시간, 시작이 오늘부터 %d일 안이다.
                """.formatted(today, rules.slotMinutes(), rules.maxHours(), rules.advanceDays());
    }
}
