package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * "오늘 브리핑" (#64의 글 버전). 앱을 열면 오늘 수업·내 예약·지금 빈 방을 한 번에 보여 준다.
 * <ul>
 *   <li>모델을 거치지 않는다: 도구 3개(my_schedule, my_reservations, room_status)를 직접 부르고 문장은 코드가 만든다.
 *       빠르고(모델 호출 0), 공짜고, 지어내지 않는다. 화면의 [듣기]는 {@code text}를 speechSynthesis로 읽으면 된다</li>
 *   <li>도구 하나가 실패해도 나머지는 보여 준다. 실패한 칸은 {@code null}과 note로 남긴다</li>
 *   <li>수업이 있는 날 대표자 예약이 없으면 {@code find_available_rooms}를 한 번 더 불러 "수업 끝나고 쓸 수 있는 가장 이른 칸"을
 *       고르고, 화면 버튼용 문장({@code suggestions}, "15:30 2번 방 2시간 잡아 줘")을 준다. 누르면 채팅으로 가서 확인 카드까지 간다</li>
 *   <li>번호·학번·이름은 도구 결과에 없다(게이트웨이가 가림). 문장에도 방·시각만 들어간다</li>
 *   <li>11차 회의(11/13) 아침 브리핑: 날씨({@code campus_weather})·교내 셔틀({@code campus_shuttle})을 앞뒤에 한 줄씩 붙인다.
 *       둘 다 덤이라 실패하거나 시간표가 없으면 문장에서 조용히 뺀다(브리핑 전체를 막지 않는다)</li>
 * </ul>
 */
@Service
public class DailyBriefing {

    private static final String CLASSES = "classes";
    private static final String START_AT = "startAt";
    private static final String END_AT = "endAt";
    private static final String ROOM_NAME = "roomName";
    private static final Logger log = LoggerFactory.getLogger(DailyBriefing.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Set<String> ACTIVE = Set.of("BOOKED", "IN_USE");

    static final String AFTER_CLASS_FAILED = "수업 뒤 빈방을 확인하지 못했어요.";
    static final String AFTER_CLASS_NONE = "수업 끝나고 오늘 쓸 수 있는 방은 없어요.";
    /** 이보다 짧은 공강은 방을 제안하지 않는다 (이동·정리 빼면 쓸 시간이 없다) */
    static final Duration MIN_FREE_PERIOD = Duration.ofHours(1);

    private final ToolGateway tools;
    private final Clock clock;
    private final JsonMapper json;
    private final AgentProperties.Rules rules;
    private final boolean campus;

    @Autowired
    public DailyBriefing(ToolGateway tools, Clock clock, JsonMapper json, AgentProperties props,
                         @Value("${mspace.agent.briefing.campus:true}") boolean campus) {
        this(tools, clock, json, props.rules(), campus);
    }

    /** 학교 생활(날씨·셔틀) 없이. 기존 테스트가 부르는 도구 순서를 그대로 보게 */
    DailyBriefing(ToolGateway tools, Clock clock, JsonMapper json, AgentProperties.Rules rules) {
        this(tools, clock, json, rules, false);
    }

    DailyBriefing(ToolGateway tools, Clock clock, JsonMapper json, AgentProperties.Rules rules, boolean campus) {
        this.tools = tools;
        this.clock = clock;
        this.json = json;
        this.rules = rules;
        this.campus = campus;
    }

    public Briefing today(UserHeaders headers) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(SEOUL));
        LocalDate today = now.toLocalDate();
        List<String> hints = new ArrayList<>();
        try (ToolGateway.Session session = tools.open(headers)) {
            JsonNode scheduleNode = call(session, "my_schedule", Map.of("date", today.toString()));
            Schedule schedule = schedule(scheduleNode, hints);
            Reservations reservations = reservations(call(session, "my_reservations", Map.of()), now);
            Rooms rooms = rooms(call(session, "room_status", Map.of()));
            Options options = options(session, scheduleNode, reservations, now);
            Campus campusInfo = campus ? campus(call(session, "campus_weather", Map.of()), call(session, "campus_shuttle", Map.of()))
                    : Campus.NONE;
            String text = text(now, schedule, options.freePeriod(), options.afterClass(), reservations, rooms, campusInfo);
            List<String> suggestions = new ArrayList<>();
            if (options.afterClass() != null && options.afterClass().suggestion() != null) {
                suggestions.add(options.afterClass().suggestion());
            }
            if (options.freePeriod() != null) {
                suggestions.add(options.freePeriod().suggestion());
            }
            return new Briefing(today.toString(), dayName(now), now.toOffsetDateTime().toString(), schedule, options.freePeriod(),
                    options.afterClass(), reservations, rooms, text, List.copyOf(hints), List.copyOf(suggestions), campusInfo);
        }
    }

    private record Options(FreePeriod freePeriod, AfterClass afterClass) {
        static final Options NONE = new Options(null, null);
    }

    /**
     * 빈방 조회 한 번(오늘 하루 전체)으로 두 가지를 고른다.
     * <ul>
     *   <li>수업 끝나고(searchFromAt부터) 쓸 수 있는 가장 이른 칸({@link AfterClass})</li>
     *   <li>가장 긴 공강(수업 사이, 끝 + 여유 올림 ~ 다음 수업 시작 내림, {@link #MIN_FREE_PERIOD} 이상)에 쓸 수 있는 칸({@link FreePeriod})</li>
     * </ul>
     * 안 보는 경우: 시간표 없음·오늘 수업 없음·이미 대표자로 잡은 예약이 있음(대표자 동시 1건이라 core가 거절한다).
     * 고르기: 가장 이른 시작 → 그 시작에서 가장 긴 길이(최대 2시간) → 방 번호. 지난 시각은 지금(칸 올림)부터 본다
     */
    private Options options(ToolGateway.Session session, JsonNode scheduleNode, Reservations reservations, ZonedDateTime now) {
        if (scheduleNode == null || "NO_SCHEDULE".equals(scheduleNode.path("code").asString(null))
                || scheduleNode.path(CLASSES).isEmpty()) {
            return Options.NONE;
        }
        if (reservations.items() != null && reservations.items().stream().anyMatch(ReservationItem::owner)) {
            return Options.NONE;
        }
        ZonedDateTime nowCeil = ceilToSlot(now);
        ZonedDateTime afterFrom = afterClassFrom(scheduleNode, now, nowCeil);
        Window gap = longestFreePeriod(scheduleNode, now, nowCeil);
        if (afterFrom == null && gap == null) {
            return Options.NONE;
        }
        JsonNode found = call(session, "find_available_rooms", Map.of("date", now.toLocalDate().toString()));
        return new Options(freePeriod(found, gap), afterClass(found, afterFrom));
    }

    /** 수업 끝나고 쓸 수 있는 가장 이른 칸. 시작을 모르면 null, 조회 실패·빈 칸 없음은 note */
    private AfterClass afterClass(JsonNode found, ZonedDateTime afterFrom) {
        if (afterFrom == null) {
            return null;
        }
        String hhmm = hhmm(afterFrom);
        if (found == null) {
            return new AfterClass(hhmm, null, null, null, 0, null, AFTER_CLASS_FAILED);
        }
        Candidate best = pick(found, afterFrom.toOffsetDateTime(), null);
        if (best == null) {
            return new AfterClass(hhmm, null, null, null, 0, null, AFTER_CLASS_NONE);
        }
        return new AfterClass(hhmm, best.roomName(), hhmm(best.start()), hhmm(best.end()), best.minutes(), best.suggestion(), null);
    }

    /** 가장 긴 공강에 쓸 수 있는 칸. 공강이 없거나 조회 실패·빈 칸 없음이면 null (브리핑에서 빼면 된다) */
    private FreePeriod freePeriod(JsonNode found, Window gap) {
        if (gap == null || found == null) {
            return null;
        }
        Candidate best = pick(found, gap.from().toOffsetDateTime(), gap.to().toOffsetDateTime());
        if (best == null) {
            return null;
        }
        return new FreePeriod(hhmm(gap.from()), hhmm(gap.to()), best.roomName(), hhmm(best.start()), hhmm(best.end()),
                best.minutes(), best.suggestion());
    }

    /** 수업 끝 + 여유(searchFromAt). 오늘이 아니면(밤늦은 수업) null. 지났으면 지금 칸, 그마저 내일이면 null */
    private static ZonedDateTime afterClassFrom(JsonNode scheduleNode, ZonedDateTime now, ZonedDateTime nowCeil) {
        OffsetDateTime searchFrom = parse(scheduleNode.path("searchFromAt").asString(null));
        if (searchFrom == null || !searchFrom.atZoneSameInstant(SEOUL).toLocalDate().equals(now.toLocalDate())) {
            return null;
        }
        ZonedDateTime from = searchFrom.atZoneSameInstant(SEOUL);
        if (nowCeil.isAfter(from)) {
            from = nowCeil;
        }
        return from.toLocalDate().equals(now.toLocalDate()) ? from : null;
    }

    /**
     * 수업 사이 가장 긴 공강. 앞 수업 끝 + 여유(bufferMinutes)를 칸으로 올리고 다음 수업 시작을 칸으로 내린 구간.
     * 이미 지난 구간은 빼고, 진행 중인 구간은 지금 칸부터 센다. {@link #MIN_FREE_PERIOD}보다 짧으면 없음
     */
    private Window longestFreePeriod(JsonNode scheduleNode, ZonedDateTime now, ZonedDateTime nowCeil) {
        List<OffsetDateTime[]> classes = new ArrayList<>();
        for (JsonNode c : scheduleNode.path(CLASSES)) {
            OffsetDateTime start = parse(c.path(START_AT).asString(null));
            OffsetDateTime end = parse(c.path(END_AT).asString(null));
            if (start != null && end != null) {
                classes.add(new OffsetDateTime[] {start, end});
            }
        }
        classes.sort(java.util.Comparator.comparing(a -> a[0].toInstant()));
        int buffer = scheduleNode.path("bufferMinutes").asInt(30);
        Window best = null;
        for (int i = 0; i + 1 < classes.size(); i++) {
            ZonedDateTime from = ceilToSlot(classes.get(i)[1].atZoneSameInstant(SEOUL).plusMinutes(buffer));
            ZonedDateTime to = floorToSlot(classes.get(i + 1)[0].atZoneSameInstant(SEOUL));
            if (nowCeil.isAfter(from)) {
                from = nowCeil;
            }
            if (!from.toLocalDate().equals(now.toLocalDate()) || Duration.between(from, to).compareTo(MIN_FREE_PERIOD) < 0) {
                continue;
            }
            if (best == null || Duration.between(from, to).compareTo(Duration.between(best.from(), best.to())) > 0) {
                best = new Window(from, to);
            }
        }
        return best;
    }

    private record Window(ZonedDateTime from, ZonedDateTime to) {
    }

    /** from 뒤(to가 있으면 그 안)에서 가장 좋은 칸. 없으면 null */
    private Candidate pick(JsonNode found, OffsetDateTime from, OffsetDateTime to) {
        Candidate best = null;
        for (JsonNode room : found.path("rooms")) {
            for (JsonNode r : room.path("free")) {
                Candidate c = candidate(room, r, from, to);
                if (c != null && (best == null || c.beats(best))) {
                    best = c;
                }
            }
        }
        return best;
    }

    /** 빈 구간 하나를 from~to로 자르고 최대 2시간으로 줄인 칸. 모양이 깨졌거나 한 칸(30분)보다 짧으면 null */
    private Candidate candidate(JsonNode room, JsonNode range, OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime start = parse(range.path(START_AT).asString(null));
        OffsetDateTime end = parse(range.path(END_AT).asString(null));
        if (start == null || end == null) {
            return null;
        }
        if (start.toInstant().isBefore(from.toInstant())) {
            start = from;
        }
        if (to != null && end.toInstant().isAfter(to.toInstant())) {
            end = to;
        }
        Duration max = Duration.ofHours(rules.maxHours());
        Duration length = Duration.between(start, end);
        if (length.compareTo(max) > 0) {
            length = max;
        }
        if (length.compareTo(Duration.ofMinutes(rules.slotMinutes())) < 0) {
            return null;
        }
        return new Candidate(room.path("roomId").asLong(0), room.path(ROOM_NAME).asString(""), start, length);
    }

    private record Candidate(long roomId, String roomName, OffsetDateTime start, Duration length) {
        OffsetDateTime end() {
            return start.plus(length);
        }

        int minutes() {
            return (int) length.toMinutes();
        }

        /** 화면 버튼 글. 누르면 채팅으로 보내 확인 카드까지 간다 */
        String suggestion() {
            return hhmm(start) + " " + roomName + " " + lengthText(minutes()) + " 잡아 줘";
        }

        /** 더 이른 시작 → 같은 시작이면 더 긴 길이 → 같으면 작은 방 번호 */
        boolean beats(Candidate other) {
            int byStart = start.toInstant().compareTo(other.start.toInstant());
            if (byStart != 0) {
                return byStart < 0;
            }
            int byLength = length.compareTo(other.length);
            return byLength != 0 ? byLength > 0 : roomId < other.roomId;
        }
    }

    /** 120 → "2시간", 90 → "1시간 30분", 30 → "30분" */
    static String lengthText(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) {
            return m + "분";
        }
        return m == 0 ? h + "시간" : h + "시간 " + m + "분";
    }

    private static ZonedDateTime ceilToSlot(ZonedDateTime t) {
        ZonedDateTime floor = floorToSlot(t);
        return floor.isBefore(t) ? floor.plusMinutes(30) : floor;
    }

    private static ZonedDateTime floorToSlot(ZonedDateTime t) {
        return t.withSecond(0).withNano(0).withMinute(t.getMinute() < 30 ? 0 : 30);
    }

    private static String hhmm(ZonedDateTime t) {
        return "%02d:%02d".formatted(t.getHour(), t.getMinute());
    }

    private static String hhmm(OffsetDateTime t) {
        return hhmm(t.atZoneSameInstant(SEOUL));
    }

    /** 도구 결과 JSON. 오류면 null (문장에서 "불러오지 못했어요") */
    private JsonNode call(ToolGateway.Session session, String name, Map<String, Object> args) {
        long started = System.nanoTime();
        ToolGateway.Outcome outcome = session.call(name, args);
        log.info("briefing tool={} ok={} {}ms", name, !outcome.isError(), (System.nanoTime() - started) / 1_000_000);
        if (outcome.isError()) {
            return null;
        }
        try {
            return json.readTree(outcome.json());
        } catch (JacksonException e) {
            log.warn("briefing tool={} 결과 해석 실패", name);
            return null;
        }
    }

    private static Schedule schedule(JsonNode node, List<String> hints) {
        if (node == null) {
            return new Schedule(null, null, "시간표를 불러오지 못했어요.");
        }
        if ("NO_SCHEDULE".equals(node.path("code").asString(null))) {
            hints.add(ChatResult.HINT_OPEN_SCHEDULE);
            return new Schedule(List.of(), null, "아직 넣은 시간표가 없어요. 넣어 두면 수업 끝나는 시각에 맞춰 빈방을 찾아 드려요.");
        }
        List<ClassItem> classes = new ArrayList<>();
        for (JsonNode c : node.path(CLASSES)) {
            classes.add(new ClassItem(c.path("name").asString(""), hhmm(c.path(START_AT).asString(null)),
                    hhmm(c.path(END_AT).asString(null))));
        }
        return new Schedule(classes, hhmm(node.path("lastClassEndAt").asString(null)), null);
    }

    /** 끝나지 않은 예약(BOOKED·IN_USE, 종료 전)만, 오늘 것 먼저 */
    private static Reservations reservations(JsonNode node, ZonedDateTime now) {
        if (node == null) {
            return new Reservations(null, "예약을 불러오지 못했어요.");
        }
        List<ReservationItem> items = new ArrayList<>();
        for (JsonNode r : node.path("reservations")) {
            OffsetDateTime start = parse(r.path(START_AT).asString(null));
            OffsetDateTime end = parse(r.path(END_AT).asString(null));
            // core 상태 갱신이 늦어도 끝난 예약은 안내하지 않는다
            boolean ended = end != null && !end.toInstant().isAfter(now.toInstant());
            if (ended || !ACTIVE.contains(r.path("status").asString(""))) {
                continue;
            }
            // 오늘 시작했거나, 전날 시작해 지금도 이어지는(자정 넘김) 예약은 오늘 예약
            boolean today = start != null && (start.atZoneSameInstant(SEOUL).toLocalDate().equals(now.toLocalDate())
                    || !start.toInstant().isAfter(now.toInstant()));
            items.add(new ReservationItem(r.path("id").asLong(0), r.path(ROOM_NAME).asString(""),
                    r.path(START_AT).asString(null), r.path(END_AT).asString(null), r.path("status").asString(""),
                    r.path("owner").asBoolean(false), today));
        }
        // 오늘 것 먼저, 그 안에서는 시작이 빠른 순 (도구는 끝나지 않은 예약끼리의 순서를 보장하지 않는다)
        items.sort(java.util.Comparator.comparing((ReservationItem i) -> !i.today())
                .thenComparing(i -> i.startAt() == null ? "" : i.startAt()));
        return new Reservations(items, null);
    }

    private static Rooms rooms(JsonNode node) {
        if (node == null) {
            return new Rooms(null, null, "방 현황을 불러오지 못했어요.");
        }
        List<String> names = new ArrayList<>();
        String earliestLimit = null;
        for (JsonNode r : node.path("rooms")) {
            String latest = r.path("latestEndAt").asString(null);
            if (latest != null) {
                names.add(r.path(ROOM_NAME).asString(""));
                if (earliestLimit == null || latest.compareTo(earliestLimit) < 0) {
                    earliestLimit = latest;
                }
            }
        }
        return new Rooms(names, hhmm(earliestLimit), null);
    }

    static String text(ZonedDateTime now, Schedule schedule, FreePeriod freePeriod, AfterClass afterClass, Reservations reservations,
                       Rooms rooms) {
        return text(now, schedule, freePeriod, afterClass, reservations, rooms, Campus.NONE);
    }

    static String text(ZonedDateTime now, Schedule schedule, FreePeriod freePeriod, AfterClass afterClass, Reservations reservations,
                       Rooms rooms, Campus campus) {
        StringBuilder sb = new StringBuilder();
        sb.append(now.getMonthValue()).append("월 ").append(now.getDayOfMonth()).append("일 ").append(dayName(now))
                .append("요일 브리핑이에요. ");
        appendWeather(sb, campus.weather());
        appendClasses(sb, schedule);
        appendOptions(sb, freePeriod, afterClass);
        appendReservations(sb, reservations);
        appendRooms(sb, rooms);
        appendShuttle(sb, campus.shuttle());
        return sb.toString().trim();
    }

    /** "오늘 광주 조선대는 비, 8~15도예요. 비 올 확률 70%라 우산을 챙기세요." 날씨를 못 받았으면 뺀다 */
    private static void appendWeather(StringBuilder sb, Weather weather) {
        if (weather == null || weather.summary() == null) {
            return;
        }
        sb.append("오늘 ").append(weather.place()).append("는 ").append(weather.summary());
        if (weather.minTemperature() != null && weather.maxTemperature() != null) {
            sb.append(", ").append(Math.round(weather.minTemperature())).append('~').append(Math.round(weather.maxTemperature()))
                    .append("도");
        }
        sb.append("예요. ");
        if (weather.umbrella() && weather.rainChance() != null) {
            sb.append("비 올 확률 ").append(weather.rainChance()).append("%라 우산을 챙기세요. ");
        }
    }

    /** "다음 교내 셔틀은 IT융합대학 앞 12:10이에요(시간표 기준)." 시간표가 없거나 남은 차가 없으면 뺀다 */
    private static void appendShuttle(StringBuilder sb, Shuttle shuttle) {
        if (shuttle == null) {
            return;
        }
        sb.append(" 다음 교내 셔틀은 ").append(shuttle.stop() == null || shuttle.stop().isBlank() ? "" : shuttle.stop() + " ")
                .append(shuttle.next()).append("이에요(시간표 기준).");
    }

    /** 날씨·셔틀 결과에서 브리핑에 쓰는 값만. 실패·없음은 null */
    private static Campus campus(JsonNode weatherNode, JsonNode shuttleNode) {
        Weather weather = null;
        if (weatherNode != null && weatherNode.path("summary").isString()) {
            weather = new Weather(weatherNode.path("place").asString(""), weatherNode.path("summary").asString(),
                    number(weatherNode.path("minTemperature")), number(weatherNode.path("maxTemperature")),
                    weatherNode.path("rainChance").isNumber() ? weatherNode.path("rainChance").asInt() : null,
                    weatherNode.path("umbrella").asBoolean(false));
        }
        Shuttle shuttle = null;
        if (shuttleNode != null && shuttleNode.path("code").asString(null) == null) { // NO_TIMETABLE이면 뺀다
            String best = null;
            String stop = null;
            for (JsonNode route : shuttleNode.path("routes")) {
                String next = route.path("next").path(0).asString(null);
                if (next != null && (best == null || next.compareTo(best) < 0)) {
                    best = next;
                    stop = route.path("stop").asString(null);
                }
            }
            if (best != null) {
                shuttle = new Shuttle(stop, best);
            }
        }
        return new Campus(weather, shuttle);
    }

    private static Double number(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    /** 수업 */
    private static void appendClasses(StringBuilder sb, Schedule schedule) {
        if (schedule.classes() == null) {
            sb.append(schedule.note()).append(' ');
            return;
        }
        if (schedule.classes().isEmpty()) {
            sb.append(schedule.note() != null ? schedule.note() : "오늘은 수업이 없어요.").append(' ');
            return;
        }
        sb.append("오늘 수업은 ");
        for (int i = 0; i < schedule.classes().size(); i++) {
            ClassItem c = schedule.classes().get(i);
            sb.append(i > 0 ? ", " : "").append(c.name()).append(' ').append(c.start()).append('–').append(c.end());
        }
        sb.append(' ').append(count(schedule.classes().size())).append("예요. 마지막 수업은 ").append(schedule.lastClassEnd())
                .append("에 끝나요. ");
    }

    /** 공강에 쓸 수 있는 칸, 수업 끝나고 쓸 수 있는 칸 */
    private static void appendOptions(StringBuilder sb, FreePeriod freePeriod, AfterClass afterClass) {
        if (freePeriod != null) {
            sb.append("공강 ").append(freePeriod.periodStart()).append('–').append(freePeriod.periodEnd()).append("에는 ")
                    .append(freePeriod.roomName()).append("을 ").append(freePeriod.start()).append("부터 ")
                    .append(lengthText(freePeriod.minutes())).append(" 쓸 수 있어요. ");
        }
        if (afterClass == null) {
            return;
        }
        if (afterClass.note() != null) {
            sb.append(afterClass.note()).append(' ');
        } else {
            sb.append("수업 끝나고 ").append(afterClass.start()).append("부터 ").append(afterClass.roomName()).append("을 ")
                    .append(lengthText(afterClass.minutes())).append(" 쓸 수 있어요. ");
        }
    }

    /** 예약 (가장 앞 한 건 + 나머지 건수) */
    private static void appendReservations(StringBuilder sb, Reservations reservations) {
        if (reservations.items() == null) {
            sb.append(reservations.note()).append(' ');
            return;
        }
        if (reservations.items().isEmpty()) {
            sb.append("잡아 둔 예약은 없어요. ");
            return;
        }
        ReservationItem r = reservations.items().getFirst();
        sb.append(r.today() ? "오늘 예약은 " : "다음 예약은 " + monthDay(r.startAt()) + " ").append(r.roomName()).append(' ')
                .append(hhmm(r.startAt())).append('–').append(hhmm(r.endAt()));
        if ("IN_USE".equals(r.status())) {
            sb.append("(사용 중)");
        }
        sb.append(r.owner() ? "이고 대표자예요. " : "이에요. ");
        if (reservations.items().size() > 1) {
            sb.append("그 뒤로 ").append(reservations.items().size() - 1).append("건이 더 있어요. ");
        }
    }

    /** 지금 빈 방 */
    private static void appendRooms(StringBuilder sb, Rooms rooms) {
        if (rooms.availableNames() == null) {
            sb.append(rooms.note());
        } else if (rooms.availableNames().isEmpty()) {
            sb.append("지금 바로 쓸 수 있는 방은 없어요.");
        } else {
            sb.append("지금 바로 쓸 수 있는 방은 ").append(String.join(", ", rooms.availableNames())).append(' ')
                    .append(count(rooms.availableNames().size())).append("예요.");
        }
    }

    private static String count(int n) {
        return switch (n) {
            case 1 -> "한 개";
            case 2 -> "두 개";
            case 3 -> "세 개";
            case 4 -> "네 개";
            case 5 -> "다섯 개";
            default -> n + "개";
        };
    }

    private static String dayName(ZonedDateTime t) {
        return t.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
    }

    private static OffsetDateTime parse(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String hhmm(String iso) {
        OffsetDateTime t = parse(iso);
        return t == null ? null : "%02d:%02d".formatted(t.atZoneSameInstant(SEOUL).getHour(), t.atZoneSameInstant(SEOUL).getMinute());
    }

    private static String monthDay(String iso) {
        OffsetDateTime t = parse(iso);
        if (t == null) {
            return "";
        }
        ZonedDateTime z = t.atZoneSameInstant(SEOUL);
        return z.getMonthValue() + "월 " + z.getDayOfMonth() + "일(" + dayName(z) + ")";
    }

    /**
     * @param text  화면에 보여 주고 [듣기]로 읽어 줄 한 문단. 방·시각만 들어 있다
     * @param hints OPEN_SCHEDULE: 시간표가 없으니 [내 시간표 넣기] 버튼
     */
    public record Briefing(String date, String dayOfWeek, String generatedAt, Schedule schedule, FreePeriod freePeriod,
                           AfterClass afterClass, Reservations reservations, Rooms rooms, String text, List<String> hints,
                           List<String> suggestions, Campus campus) {
    }

    /** 날씨·셔틀 (11차 회의 아침 브리핑). 못 받았거나 꺼 두면 둘 다 null */
    public record Campus(Weather weather, Shuttle shuttle) {
        static final Campus NONE = new Campus(null, null);
    }

    /** 오늘 날씨. 숫자는 예보 서버 값 그대로 (campus_weather) */
    public record Weather(String place, String summary, Double minTemperature, Double maxTemperature, Integer rainChance,
                          boolean umbrella) {
    }

    /** 가장 가까운 다음 셔틀 출발 (시간표 기준, campus_shuttle) */
    public record Shuttle(String stop, String next) {
    }

    /**
     * 가장 긴 공강(수업 사이)에 쓸 수 있는 칸. null이면 1시간 넘는 공강이 없거나 그 시간에 빈방이 없거나 안 본 것.
     *
     * @param periodStart 공강 시작(앞 수업 끝 + 여유, 칸 올림), periodEnd 공강 끝(다음 수업 시작, 칸 내림)
     * @param suggestion  화면 버튼 글
     */
    public record FreePeriod(String periodStart, String periodEnd, String roomName, String start, String end, int minutes,
                             String suggestion) {
    }

    /**
     * 수업 끝나고 쓸 수 있는 가장 이른 칸. null이면 안 본 것(시간표 없음·오늘 수업 없음·대표자 예약 있음).
     *
     * @param searchFrom 본 시작 시각(HH:mm). 수업 끝 + 여유, 지났으면 지금 칸
     * @param suggestion 화면 버튼 글. 누르면 채팅에 그대로 보내 확인 카드까지 간다. 칸이 없으면 null
     * @param note       칸이 없거나(AFTER_CLASS_NONE) 못 봤을 때(AFTER_CLASS_FAILED)
     */
    public record AfterClass(String searchFrom, String roomName, String start, String end, int minutes, String suggestion,
                             String note) {
    }

    /** classes가 null이면 불러오지 못한 것, 비어 있으면 시간표 없음 또는 그날 수업 없음(note로 구분) */
    public record Schedule(List<ClassItem> classes, String lastClassEnd, String note) {
    }

    public record ClassItem(String name, String start, String end) {
    }

    public record Reservations(List<ReservationItem> items, String note) {
    }

    public record ReservationItem(long id, String roomName, String startAt, String endAt, String status, boolean owner,
                                  boolean today) {
    }

    /** availableNames: 지금 바로 잡을 수 있는 방 이름. earliestLimit: 그중 가장 이른 latestEndAt(HH:mm) */
    public record Rooms(List<String> availableNames, String earliestLimit, String note) {
    }
}
