package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    private final DailyBriefing briefing;
    private final PersonaStore personas;
    private final TeamStore teams;

    /** 성향 바꾸기 지름길의 도구 이름 (결과 tools, 측정용) */
    static final String PERSONA_TOOL = "persona";
    static final String PERSONA_REPLY = "비서 성향을 '%s'로 바꿨어요. %s";

    /** "브리핑"이 들어간 말은 모델 없이 오늘 브리핑으로 바로 답한다 (설계 5.z) */
    static final String BRIEFING_WORD = "브리핑";
    static final String BRIEFING_TOOL = "briefing";

    /** 기억 없이 (테스트) */
    public AgentLoop(LlmClient llm, ToolGateway tools, AgentProperties props, Clock clock) {
        this(llm, tools, props, clock, ConversationMemory.NONE, new MemoryProperties(false, null, null, 0));
    }

    /** 브리핑 지름길 없이 (테스트) */
    public AgentLoop(LlmClient llm, ToolGateway tools, AgentProperties props, Clock clock,
                     ConversationMemory memory, MemoryProperties memoryProps) {
        this(llm, tools, props, clock, memory, memoryProps, null);
    }

    /** 성향 없이 (테스트) */
    public AgentLoop(LlmClient llm, ToolGateway tools, AgentProperties props, Clock clock,
                     ConversationMemory memory, MemoryProperties memoryProps, DailyBriefing briefing) {
        this(llm, tools, props, clock, memory, memoryProps, briefing, PersonaStore.NONE);
    }

    /** 팀 없이 (테스트) */
    public AgentLoop(LlmClient llm, ToolGateway tools, AgentProperties props, Clock clock,
                     ConversationMemory memory, MemoryProperties memoryProps, DailyBriefing briefing,
                     PersonaStore personas) {
        this(llm, tools, props, clock, memory, memoryProps, briefing, personas, TeamStore.NONE);
    }

    @Autowired
    public AgentLoop(LlmClient llm, ToolGateway tools, AgentProperties props, Clock clock,
                     ConversationMemory memory, MemoryProperties memoryProps, DailyBriefing briefing,
                     PersonaStore personas, TeamStore teams) {
        this.personas = personas;
        this.teams = teams;
        this.llm = llm;
        this.tools = tools;
        this.props = props;
        this.clock = clock;
        this.memory = memory;
        this.memoryProps = memoryProps;
        this.briefing = briefing;
    }

    /**
     * 서버가 기억한 대화가 있으면 그것을 잇고, 없을 때만 화면이 보낸 history를 쓴다 (서버 기억이 끊긴 뒤의 보조).
     * 끝나면 이번 학생 말·도구 호출·결과·답을 함께 기억한다. 모델이 답을 못 만들면(예외) 이번 차례는 기억하지 않는다.
     *
     * @param history 화면이 보낸 앞선 대화(학생·비서의 글만)
     */
    public ChatResult run(UserHeaders headers, List<LlmMessage> history, String message) {
        String key = MemberKey.of(headers);
        List<LlmMessage> remembered = new ArrayList<>(memory.load(key));
        List<LlmMessage> notes = memory.takeNotes(key); // 확인 카드 결과처럼 채팅 밖에서 생긴 글 (설계 4장 "확인 뒤")
        if (remembered.isEmpty()) {
            remembered.addAll(masked(history)); // 서버 기억이 없으면 화면 기록 (미룬 글이 있어도 그 앞 맥락은 화면이 들고 있다)
        }
        remembered.addAll(notes);
        List<LlmMessage> conversation = new ArrayList<>(trim(remembered, props.maxHistoryTurns(), memoryProps.maxChars()));
        conversation.add(new LlmMessage.User(PrivacyMask.mask(message)));
        ChatResult result;
        try {
            Optional<Persona> wanted = Persona.requested(message);
            if (wanted.isPresent()) {
                result = personaShortcut(key, wanted.get());
            } else if (isBriefingRequest(message)) {
                result = briefingShortcut(headers);
            } else {
                Persona persona = personas.load(key);
                result = converse(headers, conversation, persona, teams.load(key));
                if (persona == Persona.QUIET && !result.suggestions().isEmpty()) {
                    // 조용히: 먼저 권하지 않는다. 답 아래 빠른 답장 버튼도 지운다 (확인 카드·입실 번호 카드는 그대로)
                    result = new ChatResult(result.reply(), result.confirmations(), result.accessCodeCards(), result.toolCalls(),
                            result.stoppedByLimit(), result.tools(), result.hints(), List.of());
                }
            }
        } catch (RuntimeException e) {
            memory.restoreNotes(key, notes); // 이번 차례를 저장하지 못하니 꺼낸 글을 되돌려 다음 채팅이 받게 한다
            throw e;
        }
        // 모델 답도 다음 요청에 외부 AI로 다시 가니 학생 말과 같은 규칙으로 가려 둔다
        conversation.add(new LlmMessage.Assistant(PrivacyMask.mask(result.reply()), List.of()));
        memory.save(key, trim(conversation, props.maxHistoryTurns(), memoryProps.maxChars()));
        return result;
    }

    /**
     * "오늘 브리핑 해 줘", "브리핑"처럼 브리핑을 부르는 말. 모델을 거치면 같은 도구 셋을 매번 다른 순서·문장으로 부르는데,
     * 첫 화면 카드와 같은 {@link DailyBriefing}을 그대로 쓰면 모델 호출 0(무료 한도 절약), 수백 ms, 문장이 늘 같아 시연이 안정적이다.
     * 답은 대화 맥락에 남아 다음 질문("그럼 2번 방 잡아 줘")의 근거가 된다
     */
    boolean isBriefingRequest(String message) {
        return briefing != null && message != null && message.contains(BRIEFING_WORD);
    }

    /**
     * "잔소리 모드로 해 줘"처럼 성향을 고르는 말. 모델 없이 저장하고 바로 답한다(설계 5.v). 저장 실패는 예외로 올라가
     * 503 MEMORY_UNAVAILABLE — 바뀐 척하지 않는다. 빠른 답장에는 나머지 성향으로 바꾸는 말을 둔다
     */
    private ChatResult personaShortcut(String key, Persona persona) {
        personas.save(key, persona);
        List<String> suggestions = Persona.all().stream().filter(p -> p != persona)
                .map(p -> p.label() + " 모드로 바꿔 줘").toList();
        return new ChatResult(PERSONA_REPLY.formatted(persona.label(), persona.description()), List.of(), List.of(), 0,
                false, List.of(PERSONA_TOOL), List.of(), suggestions);
    }

    private ChatResult briefingShortcut(UserHeaders headers) {
        long started = System.nanoTime();
        DailyBriefing.Briefing b = briefing.today(headers);
        log.info("briefing shortcut {}ms", (System.nanoTime() - started) / 1_000_000);
        return new ChatResult(b.text(), List.of(), List.of(), 0, false, List.of(BRIEFING_TOOL), b.hints(), b.suggestions());
    }

    /** 화면이 보낸 글은 학생이 고쳐 보낼 수 있어 비서 차례도 가린다 */
    private static List<LlmMessage> masked(List<LlmMessage> history) {
        List<LlmMessage> out = new ArrayList<>(history.size());
        for (LlmMessage m : history) {
            out.add(switch (m) {
                case LlmMessage.User(String text) -> new LlmMessage.User(PrivacyMask.mask(text));
                case LlmMessage.Assistant(String text, List<ToolCall> calls) -> new LlmMessage.Assistant(PrivacyMask.mask(text), calls);
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
        int start = conversation.size(); // 상한 안에 드는 차례가 하나도 없으면 빈 목록 (가장 최근 차례 하나가 너무 커도 상한을 지킨다)
        for (int i = conversation.size() - 1; i >= 0; i--) {
            LlmMessage m = conversation.get(i);
            chars += switch (m) {
                case LlmMessage.User(String text) -> text.length();
                case LlmMessage.Assistant(String text, var ignored) -> text == null ? 0 : text.length();
                case LlmMessage.ToolResult(var id, var name, String json, var error) -> json == null ? 0 : json.length();
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

    private ChatResult converse(UserHeaders headers, List<LlmMessage> conversation, Persona persona, List<Team> myTeams) {
        Progress progress = new Progress();
        // 모델 9번×20초 + 도구 8번×10초면 nginx(60초)가 먼저 끊어 504가 나고, 발급된 확인 카드가 화면에 안 간다.
        // 그래서 모델·도구를 새로 부르기 전마다 남은 시간을 보고, 넘었으면 지금까지의 카드와 함께 멈춘다.
        // 이미 시작한 호출 하나는 끊지 않으므로 최악은 기한 + 호출 하나의 제한(Gemini 20초, 도구 10초)이다
        Instant deadline = clock.instant().plus(props.chatTimeout());

        try (ToolGateway.Session session = tools.open(headers)) {
            String system = systemPrompt(persona, myTeams);
            while (true) {
                if (progress.calls > 0 && pastDeadline(deadline, progress.calls)) {
                    return progress.result(TIME_LIMIT_REPLY, true);
                }
                LlmReply reply;
                try {
                    reply = llm.chat(system, conversation, session.tools());
                } catch (LlmException e) {
                    if (!progress.hasCards()) {
                        throw e;
                    }
                    // 카드는 이미 만들어졌다(확인 토큰 발급, 대표자 확인). 모델 답만 실패했으니 카드는 화면에 준다
                    log.warn("agent llm failed after cards code={}", e.code());
                    return progress.result(CARDS_ONLY_REPLY, false);
                }
                if (!reply.wantsTools()) {
                    return progress.result(grounded(reply.text(), progress.lookedUpAt, progress.usedSchedule), false);
                }
                if (progress.calls + reply.toolCalls().size() > props.maxToolCalls()) {
                    log.warn("agent tool limit reached calls={} max={}", progress.calls, props.maxToolCalls());
                    return progress.result(LIMIT_REPLY, true);
                }
                // 모델 답과 도구 결과도 다음 요청에 다시 실려 외부 AI로 간다. 학생 말과 같은 규칙으로 가린다.
                // 지금 도구는 학번·전화번호·입실 번호를 돌려주지 않지만, 도구가 늘어도 이 한 곳에서 막는다
                conversation.add(new LlmMessage.Assistant(PrivacyMask.mask(reply.text()), reply.toolCalls()));
                if (!runCalls(session, reply.toolCalls(), conversation, progress, deadline)) {
                    return progress.result(TIME_LIMIT_REPLY, true);
                }
            }
        }
    }

    /**
     * 모델이 고른 도구를 차례로 실행하고 결과를 대화에 붙인다.
     * 기한을 넘기면 실행하지 않은 호출에도 결과를 붙이고 false. 짝이 안 맞는 호출이 기억에 남으면 다음 요청마다 모델이 400을 낸다
     */
    private boolean runCalls(ToolGateway.Session session, List<ToolCall> wanted, List<LlmMessage> conversation,
                             Progress progress, Instant deadline) {
        for (int i = 0; i < wanted.size(); i++) {
            ToolCall call = wanted.get(i);
            Instant now = clock.instant(); // 기한 검사와 꼬리말의 조회 시각에 같이 쓴다 (시계는 한 번만 읽는다)
            if (pastDeadline(now, deadline, progress.calls)) {
                for (ToolCall rest : wanted.subList(i, wanted.size())) {
                    conversation.add(new LlmMessage.ToolResult(rest.id(), rest.name(), SKIPPED_RESULT, true));
                }
                return false;
            }
            progress.calls++;
            progress.toolNames.add(call.name());
            long started = System.nanoTime();
            ToolGateway.Outcome outcome = session.call(call.name(), call.arguments());
            if (log.isInfoEnabled()) {
                log.info("agent tool={} ok={} {}ms", loggable(call.name(), session), !outcome.isError(),
                        (System.nanoTime() - started) / 1_000_000);
            }
            progress.add(call.name(), outcome, now);
            // 도구 결과는 게이트웨이가 이미 가렸다(JSON 문자열 값만). 여기서 JSON 전체에 정규식을 걸면
            // 6자리 숫자 값이 바뀌어 JSON이 깨진다 (ToolGateway.Outcome 참고)
            conversation.add(new LlmMessage.ToolResult(call.id(), call.name(), outcome.json(), outcome.isError()));
        }
        return true;
    }

    /** 한 번의 채팅에서 모은 것: 확인·입실 번호 카드, 부른 도구, 화면 신호, 꼬리말 근거, 빠른 답장 규칙용 결과 */
    private final class Progress {
        final List<Confirmation> confirmations = new ArrayList<>();
        final List<AccessCodeCard> cards = new ArrayList<>();
        final List<String> toolNames = new ArrayList<>(); // 부른 순서대로. 결과의 tools (측정용)
        final List<String> hints = new ArrayList<>(); // 화면 신호 (ChatResult.HINT_*)
        final List<QuickReplies.Seen> seen = new ArrayList<>();
        int calls;
        Instant lookedUpAt; // 마지막으로 예약 도구가 성공한 시각. 꼬리말의 근거 시각 (실패만 있으면 꼬리말 없음)
        boolean usedSchedule; // my_schedule로 학생이 넣은 시간표를 봤는지. 꼬리말에 "입력한 시간표 기준"

        boolean hasCards() {
            return !confirmations.isEmpty() || !cards.isEmpty();
        }

        void add(String tool, ToolGateway.Outcome outcome, Instant now) {
            if (!outcome.isError()) {
                if (SCHEDULE_TOOL.equals(tool) || WEEK_PLAN_TOOL.equals(tool)) {
                    sawSchedule(outcome.json());
                }
                if (!SCHEDULE_TOOL.equals(tool)) {
                    lookedUpAt = now; // week_plan도 예약 시스템(빈방)을 본 결과라 조회 시각을 남긴다
                }
            }
            seen.add(new QuickReplies.Seen(tool, outcome.json(), outcome.isError()));
            outcome.confirmation().ifPresent(confirmations::add);
            outcome.accessCodeCard().filter(c -> !cards.contains(c)).ifPresent(cards::add);
        }

        /** 시간표가 없다는 답(NO_SCHEDULE)은 시간표를 본 근거가 아니다. 대신 화면에 [내 시간표] 버튼 신호를 준다 */
        private void sawSchedule(String json) {
            boolean noSchedule = NO_SCHEDULE.matcher(json).find();
            usedSchedule |= !noSchedule;
            if (noSchedule && !hints.contains(ChatResult.HINT_OPEN_SCHEDULE)) {
                hints.add(ChatResult.HINT_OPEN_SCHEDULE);
            }
        }

        /** 멈춘 대화(한도·시간 초과)에는 빠른 답장을 안 띄운다. 이어 가라고 부추기면 또 멈춘다 */
        ChatResult result(String reply, boolean stopped) {
            List<String> suggestions;
            if (stopped) {
                suggestions = List.of();
            } else if (!hasCards() && QuickReplies.asksHeadcount(reply)) {
                suggestions = QuickReplies.HEADCOUNT; // 간단히 예약하기: 인원은 눌러서 답하게 (FR-AGT-105)
                if (!hints.contains(ChatResult.HINT_PICK_FRIENDS)) {
                    hints.add(ChatResult.HINT_PICK_FRIENDS); // 화면은 친구 버튼으로 "누구랑?"을 받는다. 이름은 모델로 안 감
                }
            } else {
                // 연장 길이·언제를 되물으면 그 답을 버튼으로 (한 번 눌러 이어 감)
                suggestions = QuickReplies.pick(
                        hasCards() ? List.of() : QuickReplies.forQuestion(reply, hints.contains(ChatResult.HINT_OPEN_SCHEDULE)),
                        QuickReplies.suggest(seen, hasCards(), lastAllowedDay()));
            }
            return new ChatResult(reply, confirmations, cards, calls, stopped, List.copyOf(toolNames), List.copyOf(hints),
                    suggestions);
        }

        /** 조회할 수 있는 마지막 날: 오늘(서울) + 미리 예약 일수 - 1 */
        private java.time.LocalDate lastAllowedDay() {
            return java.time.LocalDate.now(clock.withZone(SEOUL)).plusDays(props.rules().advanceDays() - 1L);
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

    /** 시간 상한으로 실행하지 않은 도구 호출의 결과 자리 (기억의 호출·결과 짝을 지킨다) */
    static final String SKIPPED_RESULT = "{\"message\":\"시간이 오래 걸려 실행하지 않았어요\"}";
    static final String CARDS_ONLY_REPLY = "AI 비서의 답이 끊겼어요. 아래 카드는 그대로 쓸 수 있어요.";

    /**
     * 도구를 하나라도 불러 낸 답에는 조회 시각을 꼬리말로 붙인다. "그걸 어떻게 알아?"에 모델의 말이 아니라
     * 코드가 근거를 보여 주는 것이라, 모델이 사과하거나 지어내도 학생은 조회 사실을 알 수 있다 (11/5 시연 피드백)
     */
    String grounded(String text, Instant lookedUpAt) {
        return grounded(text, lookedUpAt, false);
    }

    /**
     * 시간표를 본 답에는 "입력한 시간표 기준"을 붙인다 (재웅 기획서: 답의 근거). 예약 도구도 불렀으면 한 줄에 같이:
     * "(예약 시스템 15:00 조회 · 입력한 시간표 기준)"
     */
    String grounded(String text, Instant lookedUpAt, boolean usedSchedule) {
        // 모델이 앞선 답을 흉내 내 꼬리말을 직접 쓰면 지우고(두 번 붙던 문제, #74), 도구 이름은 학생 말로 바꾼다
        text = ReplyPolish.polish(text);
        if ((lookedUpAt == null && !usedSchedule) || text == null || text.isBlank()) {
            return text;
        }
        if (lookedUpAt == null) {
            return text.stripTrailing() + SCHEDULE_SUFFIX;
        }
        ZonedDateTime at = lookedUpAt.atZone(SEOUL);
        String suffix = usedSchedule ? BOTH_SUFFIX : GROUNDING_SUFFIX;
        return text.stripTrailing() + suffix.formatted(at.getHour(), at.getMinute());
    }

    static final String SCHEDULE_TOOL = "my_schedule";
    static final String WEEK_PLAN_TOOL = "week_plan";

    /** 최상위 "code":"NO_SCHEDULE"만. 과목명에 같은 글자가 있어도 시간표 없음으로 보지 않는다 */

    static final java.util.regex.Pattern NO_SCHEDULE = java.util.regex.Pattern.compile("\"code\"\\s*:\\s*\"NO_SCHEDULE\"");
    static final String GROUNDING_SUFFIX = "\n\n(예약 시스템 %02d:%02d 조회 기준)";
    static final String SCHEDULE_SUFFIX = "\n\n(입력한 시간표 기준)";
    static final String BOTH_SUFFIX = "\n\n(예약 시스템 %02d:%02d 조회 · 입력한 시간표 기준)";

    String systemPrompt() {
        return systemPrompt(Persona.DEFAULT);
    }

    /** 성향(설계 5.v)은 지시문 끝에 한 토막으로 붙는다. 안전 규칙은 성향과 상관없이 같다 */
    String systemPrompt(Persona persona) {
        return systemPrompt(persona, List.of());
    }

    /**
     * 학생이 저장한 팀은 이름과 인원만 지시문 끝에 붙인다(멤버 학번·이름은 넣지 않음, FR-AGT-105).
     * 팀을 고른 뒤 누구를 넣을지는 화면이 확인 카드에 채운다
     */
    static String teamRule(List<Team> myTeams) {
        if (myTeams == null || myTeams.isEmpty()) {
            return "";
        }
        String list = myTeams.stream().map(t -> "%s(나 포함 %d명)".formatted(PrivacyMask.mask(t.name()), t.headcount()))
                .collect(java.util.stream.Collectors.joining(", "));
        return "- 학생이 저장한 팀: " + list + ". \"팀 A랑\"처럼 팀 이름을 말하면 함께할 사람과 인원은 그 팀으로 보고 다시 묻지 않는다"
                + "(headcount = 그 인원). 팀원이 누구인지는 너도 모르고 묻지 않는다. 확인 카드에 화면이 팀원을 채운다.\n";
    }

    String systemPrompt(Persona persona, List<Team> myTeams) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(SEOUL));
        AgentProperties.Rules rules = props.rules();
        String today = "%s(%s) %02d:%02d".formatted(now.toLocalDate(),
                now.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN), now.getHour(), now.getMinute());
        return """
                너는 조선대 IT융합대학 M-SPACE(회의·스터디 방 1~5번) 예약 비서다. 한국어 해요체로 짧게 답한다.
                지금은 %s, 서울 시간이다. "내일", "금요일" 같은 말은 이 날짜를 기준으로 YYYY-MM-DD로 바꿔 도구에 넣는다.
                규칙:
                - 빈 시간은 find_available_rooms로 확인하고, 없는 사실을 지어내지 않는다.
                - "지금", "바로", "당장"처럼 지금부터 쓰고 싶으면 find_available_rooms가 아니라 room_status로 지금 비어 있는 방을 보고,
                  start_now_reservation으로 제안한다. 끝 시각은 room_status의 latestEndAt 안에서 고른다. 시작 시각은 묻지 않는다(확인을 누른 시각).
                  끝 시각은 지금 시각에 쓸 시간을 더한 뒤 다음 정각이나 30분으로 올린다(14:17에 1시간 → 15:30, 09:40에 2시간 → 12:00). 15:17처럼 칸 중간으로 끝내지 않는다.
                - create_reservation·start_now_reservation·cancel_reservation·checkout_reservation·continue_reservation은 제안만 한다. 결과의 summary를 보여 주고
                  "화면의 [확인]을 눌러 주세요"라고 안내한다. 예약·취소·퇴실·연장이 끝났다고 말하지 않는다.
                - 연장은 사용 중인 예약이 끝나기 10분 전부터, 30분 단위로 최대 2시간, 한 번만 된다. 몇 분 늘릴지 모르면 물어본다.
                - 쓰기 도구(create·start_now·cancel·checkout·continue)는 학생이 "잡아 줘·예약해 줘·취소해 줘·연장해 줘"처럼 하라고 했고
                  필요한 값이 다 정해졌을 때만 부른다(아래 기본값 2시간, 도구로 확인한 방·시각도 정해진 값이다). "~있어?", "~있나"처럼 묻기만 하면 찾은 방을 보여 주고 "이대로 잡아 드릴까요?"라고 묻는다.
                  "연장해 줘"처럼 몇 분인지 없으면 continue_reservation을 부르지 말고 "몇 분 늘릴까요? (30분 단위, 최대 2시간)"라고 묻는다.
                - 바로 앞 답에서 확인 카드를 띄웠고 학생이 그 카드의 날짜·시각·길이·인원을 바꿔 달라고 할 때만("다음 날로", "30분 늦춰서", "두 명 빠졌어"):
                  아직 예약이 아니니 내 예약을 찾지 않는다. 바뀐 값만 고치고 나머지(방 포함)는 앞 카드 그대로 두고, 되묻지 말고 바로 create_reservation을 바뀐 값으로 다시 부른다.
                  앞에 카드가 없는데 묻기만 하는 말("~있어?")이면 이 규칙을 쓰지 않는다.
                - 입실 번호는 show_access_code로만 안내한다. 번호는 화면 카드에만 나오고 너는 모른다. 번호를 지어내지 않는다.
                - 학번·전화번호·이름을 묻지 않는다. 함께 쓰는 사람의 학번은 확인 화면에서 받는다.
                - 도구 결과의 note·message 문장은 바꿔 말하지 않고 그대로 전하고, 할 수 있는 다음 행동을 하나 제안한다.
                  "빈방이 없다"와 "인원에 맞는 방이 없다"는 다른 이유다. 결과에 적힌 이유만 말한다.
                - "수업 끝나고", "마지막 수업 30분 전", "오늘 수업 뭐 있어"처럼 수업 시각이 필요하면 먼저 my_schedule을
                  그날 날짜로 부른다. 끝나는 시각을 학생에게 되묻지 않는다. "수업 끝나고"는 결과의 searchFromAt을
                  find_available_rooms의 from에 그대로 넣고, 시각을 직접 계산하지 않는다. code가 NO_SCHEDULE이면
                  note를 그대로 전하고 수업 시각을 지어내지 않는다(학생이 시각을 직접 말하면 그 시각으로 찾는다).
                  그날 수업이 없으면 그렇다고 말하고 원하는 시각을 묻는다.
                - 수업 끝난 뒤로 찾았는데 그날 빈방이 없으면(rooms가 비거나 free가 모두 비면) 되묻지 않고 다음 날을 이어서 본다:
                  다음 날 날짜로 my_schedule을 다시 부르고 그 searchFromAt부터 find_available_rooms를 부른다. 다음 날이
                  주말이거나 수업이 없으면 오전 9시부터 본다. 답에는 "오늘은 없어서 내일 기준으로 찾았어요"처럼 날짜가
                  바뀐 이유를 먼저 말한다. 이틀 다 없으면 멈추고 원하는 날짜를 묻는다(사흘째까지 혼자 넘어가지 않는다).
                  다음 날로 넘어가지 않는 경우: 빈방이 없는 이유가 인원(결과 note에 인원 안내)이면 날짜가 아니라 인원을
                  바꾸라고 안내한다. 다음 날이 조회 범위(오늘부터 %4$d일) 밖이면 부르지 말고 그렇다고 말한다. 다음 날
                  searchFromAt의 날짜가 그 다음 날로 넘어가면(밤늦은 수업) 그 시각으로 찾지 말고 학생에게 시각을 묻는다.
                - "이번 주에 언제 비어", "수업 끝나고 2시간 쓸 수 있는 날", "다음 주 중에"처럼 여러 날을 물으면 날짜마다
                  my_schedule·find_available_rooms를 반복하지 말고 week_plan 한 번으로 본다(길이는 minutes로). days의 option이
                  있는 날을 날짜·시각·방 그대로 나열하고, 없는 날은 reason을 전한다. 하루만 물으면 week_plan을 쓰지 않는다.
                  week_plan은 오늘부터 from~to까지만 본다. 학생이 물은 범위("다음 주")와 겹치는 날만 답하고, 결과 밖의
                  날짜는 아직 볼 수 없다고 말한다(물은 범위가 to 뒤면 to까지만 봤다고 먼저 말한다).
                - "지난번처럼", "저번 주랑 똑같이", "같은 걸로 또"처럼 전에 한 예약을 되풀이하라고 하면 먼저 my_reservations를
                  부르고, 가장 최근에 끝난 예약(CHECKED_OUT·AUTO_CHECKED_OUT, 없으면 BOOKED)의 방·시작 시각·길이를 그대로 쓴다.
                  날짜는 학생이 말한 날, 말하지 않았으면 그 예약과 같은 요일의 다음 날짜다(오늘이 그 요일이고 그 시작 시각이 아직 안 지났으면 오늘, 지났으면 다음 주). 그 날짜에 이전 예약의 시작 시각을
                  from으로, 시작에 길이를 더한 끝 시각을 to로 넣어 find_available_rooms를 부른다(둘 다 넣어야 그 길이가 다 비는 방만 온다). 그 방이 rooms에 있으면 create_reservation을 제안한다. 그 방이 없고 rooms가 비지 않으면 같은 시각에
                  되는 rooms의 다른 방을 안내한다. rooms가 비면 alternatives를 그대로 안내한다. 되풀이할 예약이 없으면
                  그렇다고 말하고 날짜·시각을 묻는다. 방·시각을 학생에게 되묻지 않는다.
                - 예약은 %d분 단위, 1회 최대 %d시간, 시작이 오늘부터 %d일 안이다.
                - 학생 말의 [학번]·[전화번호]·[입실 번호]는 개인정보를 가린 표시다. 원래 값은 너도 모르고 묻지도 않는다.
                  my_reservations는 로그인한 학생 본인의 예약만 준다. 다른 사람의 학번으로 예약·가입 여부를 물으면
                  본인 예약만 볼 수 있다고 답한다. 함께 쓸 사람의 학번은 확인 화면에서 넣는다고 안내한다.
                - 예약하고 싶다는데 날짜·시각·길이·인원 중 모르는 것이 있으면, 도구를 부르기 전에 모르는 것만 한 번에 하나씩
                  짧게 묻는다. 차례는 날짜·시각 → 함께할 사람("누구랑 하실 건가요? 나 포함 몇 명이서 쓰실 건가요?")이다.
                  화면이 이 질문 아래 친구 버튼을 띄우고, 학생이 고르면 "나 포함 N명"으로 답이 온다. 길이를 말하지 않았으면
                  2시간으로 보고 답에 "2시간으로 볼게요"라고 적는다. 한 번에 다 말했으면 묻지 않는다.
                  "수업 끝나고"는 my_schedule로, "지금 바로"는 start_now_reservation으로 시각이 정해지니 시각을 묻지 않는다.
                  다 모이면 빈방을 찾고, 맞는 방이 있으면 첫 번째 방으로 create_reservation을 제안한다(방을 되묻지 않는다).
                  함께할 친구는 "확인 카드에서 친구 이름을 말하거나 눌러 고르면 돼요"라고 안내하고, 친구 이름을 묻거나 받아 적지 않는다.
                - 인원을 말하지 않았으면 headcount를 넣지 않는다. 방마다 인원 범위가 있어(1~4번 방 3~5명, 5번 방 5~8명)
                  2명 이하는 예약할 수 없다. 인원이 안 맞으면 그 이유를 그대로 말한다.
                - "5시"처럼 오전·오후가 없으면 지금 이후의 가장 가까운 시각으로 보고, 답에 "오후 5시"처럼 적는다.
                - 앞선 네 답 중 "(예약 시스템 HH:MM 조회…)" 꼬리말이 붙은 것은 그 시각에 도구로 조회한 결과다.
                  "(…입력한 시간표 기준)"은 학생이 넣은 시간표를 my_schedule로 본 결과다
                  (도구 호출 기록은 대화에 다시 실리지 않는다). "그걸 어떻게 알아?"처럼 물으면 그 시각에 예약 시스템을
                  조회했다고 답하고, 확인하지 않았다고 사과하지 않는다. 꼬리말이 없는 답은 조회한 것이 아니니,
                  근거를 묻거나 다시 확인해 달라면 그 자리에서 도구를 불러 답한다.
                - "잠시만 기다려 주세요", "확인해 드릴게요"처럼 나중에 하겠다는 말은 하지 않는다. 네 답이 나가면
                  대화 차례가 끝나므로, 할 일은 이번 답 안에서 도구를 불러 끝낸다.
                - 꼬리말 "(예약 시스템 … 조회 기준)"은 네가 쓰지 않는다. 코드가 도구를 부른 답 끝에 한 번 붙인다.
                - 답에 도구 이름(find_available_rooms 같은 영문 snake_case)을 쓰지 않는다. 학생 말로 쓴다:
                  빈방 찾기, 지금 빈 방, 내 예약, 이용 규칙, 예약, 지금 바로 쓰기, 예약 취소, 퇴실, 연장, 내 시간표, 주간 계획.
                """.formatted(today, rules.slotMinutes(), rules.maxHours(), rules.advanceDays())
                + teamRule(myTeams) + persona.promptRule() + "\n";
    }
}
