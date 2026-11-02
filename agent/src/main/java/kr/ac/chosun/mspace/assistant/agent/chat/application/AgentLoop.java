package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult.Confirmation;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmReply;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트 반복: 모델 답에 도구 호출이 있으면 실행 → 결과를 대화에 붙여 다시 묻기 → 도구 호출이 없을 때까지.
 * 도구 호출은 대화 한 번에 최대 maxToolCalls번. 넘으면 멈추고 학생에게 알린다.
 * 로그에는 도구 이름·걸린 시간·성공 여부만 남긴다 (사용자 말 원문·토큰 금지).
 */
@Service
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final String LIMIT_REPLY = "요청을 처리하는 단계가 너무 많아져서 멈췄어요. 날짜·시간·인원을 넣어 조금 더 구체적으로 말해 주세요.";

    private final LlmClient llm;
    private final ToolGateway tools;
    private final AgentProperties props;
    private final JsonMapper json;
    private final Clock clock;

    public AgentLoop(LlmClient llm, ToolGateway tools, AgentProperties props, JsonMapper json, Clock clock) {
        this.llm = llm;
        this.tools = tools;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param history 앞선 대화(학생·비서의 글만). 도구 결과는 다시 보내지 않는다
     */
    public ChatResult run(UserHeaders headers, List<LlmMessage> history, String message) {
        List<LlmMessage> conversation = new ArrayList<>();
        for (LlmMessage m : history) {
            conversation.add(m instanceof LlmMessage.User u ? new LlmMessage.User(PrivacyMask.mask(u.text())) : m);
        }
        conversation.add(new LlmMessage.User(PrivacyMask.mask(message)));
        List<Confirmation> confirmations = new ArrayList<>();
        int calls = 0;

        try (ToolGateway.Session session = tools.open(headers)) {
            String system = systemPrompt();
            while (true) {
                LlmReply reply = llm.chat(system, conversation, session.tools());
                if (!reply.wantsTools()) {
                    return new ChatResult(reply.text(), confirmations, calls, false);
                }
                if (calls + reply.toolCalls().size() > props.maxToolCalls()) {
                    log.warn("agent tool limit reached calls={} max={}", calls, props.maxToolCalls());
                    return new ChatResult(LIMIT_REPLY, confirmations, calls, true);
                }
                conversation.add(new LlmMessage.Assistant(reply.text(), reply.toolCalls()));
                for (ToolCall call : reply.toolCalls()) {
                    calls++;
                    long started = System.nanoTime();
                    ToolGateway.Outcome outcome = session.call(call.name(), call.arguments());
                    log.info("agent tool={} ok={} {}ms", call.name(), !outcome.isError(),
                            (System.nanoTime() - started) / 1_000_000);
                    String forModel = outcome.isError() ? outcome.json() : takeConfirmation(call.name(), outcome.json(), confirmations);
                    conversation.add(new LlmMessage.ToolResult(call.id(), call.name(), forModel, outcome.isError()));
                }
            }
        }
    }

    /**
     * 쓰기 도구 결과에서 확인 토큰을 빼서 화면용 목록에 담고, 모델에는 토큰 없는 결과를 준다.
     * 모델은 확인 API를 부를 수 없지만, 토큰이 답 문장에 섞여 나가는 일도 막는다.
     */
    @SuppressWarnings("unchecked")
    private String takeConfirmation(String tool, String result, List<Confirmation> confirmations) {
        Map<String, Object> body;
        try {
            body = json.readValue(result, LinkedHashMap.class);
        } catch (RuntimeException e) {
            return result;
        }
        Object token = body.remove("confirmToken");
        if (token == null) {
            return result;
        }
        Object notes = body.get("notes");
        Object needs = body.get("needsParticipants");
        confirmations.add(new Confirmation(tool, token.toString(), String.valueOf(body.get("summary")),
                notes instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(),
                needs instanceof Number n ? n.intValue() : 0, String.valueOf(body.get("expiresAt"))));
        body.put("confirmation", "학생 화면에 [확인] 카드로 보여 줬음. 학생이 눌러야 실행됨");
        return json.writeValueAsString(body);
    }

    String systemPrompt() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(SEOUL));
        String today = "%s(%s) %02d:%02d".formatted(now.toLocalDate(),
                now.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN), now.getHour(), now.getMinute());
        return """
                너는 조선대 IT융합대학 M-SPACE(회의·스터디 방 1~5번) 예약 비서다. 한국어 해요체로 짧게 답한다.
                지금은 %s, 서울 시간이다. "내일", "금요일" 같은 말은 이 날짜를 기준으로 YYYY-MM-DD로 바꿔 도구에 넣는다.
                규칙:
                - 빈 시간은 find_available_rooms로 확인하고, 없는 사실을 지어내지 않는다.
                - create_reservation·cancel_reservation·checkout_reservation은 제안만 한다. 결과의 summary를 보여 주고
                  "화면의 [확인]을 눌러 주세요"라고 안내한다. 예약·취소·퇴실이 끝났다고 말하지 않는다.
                - 학번·전화번호·이름을 묻지 않는다. 함께 쓰는 사람의 학번은 확인 화면에서 받는다.
                - 도구가 오류를 주면 그 문장을 학생에게 전하고, 할 수 있는 다음 행동을 하나 제안한다.
                - 수업 시간표는 아직 모른다. "수업 끝나고"처럼 말하면 끝나는 시각을 물어본다.
                - 예약은 30분 단위, 1회 최대 2시간, 시작이 오늘부터 7일 안이다.
                """.formatted(today);
    }
}
