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
 * </ul>
 */
@Service
public class DailyBriefing {

    private static final Logger log = LoggerFactory.getLogger(DailyBriefing.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Set<String> ACTIVE = Set.of("BOOKED", "IN_USE");

    static final String AFTER_CLASS_FAILED = "수업 뒤 빈방을 확인하지 못했어요.";
    static final String AFTER_CLASS_NONE = "수업 끝나고 오늘 쓸 수 있는 방은 없어요.";

    private final ToolGateway tools;
    private final Clock clock;
    private final JsonMapper json;
    private final AgentProperties.Rules rules;

    @Autowired
    public DailyBriefing(ToolGateway tools, Clock clock, JsonMapper json, AgentProperties props) {
        this(tools, clock, json, props.rules());
    }

    DailyBriefing(ToolGateway tools, Clock clock, JsonMapper json, AgentProperties.Rules rules) {
        this.tools = tools;
        this.clock = clock;
        this.json = json;
        this.rules = rules;
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
            AfterClass afterClass = afterClass(session, scheduleNode, reservations, now);
            String text = text(now, schedule, afterClass, reservations, rooms);
            List<String> suggestions = afterClass != null && afterClass.suggestion() != null ? List.of(afterClass.suggestion()) : List.of();
            return new Briefing(today.toString(), dayName(now), now.toOffsetDateTime().toString(), schedule, afterClass, reservations,
                    rooms, text, List.copyOf(hints), suggestions);
        }
    }

    /**
     * 수업 끝나고(searchFromAt부터) 오늘 쓸 수 있는 가장 이른 칸. 안 보는 경우(null): 시간표 없음·오늘 수업 없음·searchFromAt이
     * 오늘이 아님(밤늦은 수업)·이미 대표자로 잡은 예약이 있음(대표자 동시 1건이라 core가 거절한다).
     * 고르기: 가장 이른 시작 → 그 시작에서 가장 긴 길이(최대 2시간) → 방 번호. 지금이 수업 끝보다 늦으면 지금(칸 올림)부터 본다
     */
    private AfterClass afterClass(ToolGateway.Session session, JsonNode scheduleNode, Reservations reservations, ZonedDateTime now) {
        if (scheduleNode == null || "NO_SCHEDULE".equals(scheduleNode.path("code").asString(null))
                || scheduleNode.path("classes").isEmpty()) {
            return null;
        }
        OffsetDateTime searchFrom = parse(scheduleNode.path("searchFromAt").asString(null));
        if (searchFrom == null || !searchFrom.atZoneSameInstant(SEOUL).toLocalDate().equals(now.toLocalDate())) {
            return null;
        }
        if (reservations.items() != null && reservations.items().stream().anyMatch(ReservationItem::owner)) {
            return null;
        }
        ZonedDateTime from = searchFrom.atZoneSameInstant(SEOUL);
        ZonedDateTime nowCeil = ceilToSlot(now);
        if (nowCeil.isAfter(from)) {
            from = nowCeil;
            if (!from.toLocalDate().equals(now.toLocalDate())) {
                return null; // 자정 직전: 오늘 칸이 없다
            }
        }
        String hhmm = "%02d:%02d".formatted(from.getHour(), from.getMinute());
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("date", now.toLocalDate().toString());
        args.put("from", hhmm);
        JsonNode found = call(session, "find_available_rooms", args);
        if (found == null) {
            return new AfterClass(hhmm, null, null, null, 0, null, AFTER_CLASS_FAILED);
        }
        Candidate best = null;
        Duration max = Duration.ofHours(rules.maxHours());
        Duration slot = Duration.ofMinutes(rules.slotMinutes());
        for (JsonNode room : found.path("rooms")) {
            for (JsonNode r : room.path("free")) {
                OffsetDateTime start = parse(r.path("startAt").asString(null));
                OffsetDateTime end = parse(r.path("endAt").asString(null));
                if (start == null || end == null) {
                    continue;
                }
                if (start.toInstant().isBefore(from.toInstant())) {
                    start = from.toOffsetDateTime();
                }
                Duration length = Duration.between(start, end);
                if (length.compareTo(max) > 0) {
                    length = max;
                }
                if (length.compareTo(slot) < 0) {
                    continue;
                }
                Candidate c = new Candidate(room.path("roomId").asLong(0), room.path("roomName").asString(""), start, length);
                if (best == null || c.beats(best)) {
                    best = c;
                }
            }
        }
        if (best == null) {
            return new AfterClass(hhmm, null, null, null, 0, null, AFTER_CLASS_NONE);
        }
        int minutes = (int) best.length().toMinutes();
        String start = hhmm(best.start().toString());
        String suggestion = start + " " + best.roomName() + " " + lengthText(minutes) + " 잡아 줘";
        return new AfterClass(hhmm, best.roomName(), start, hhmm(best.start().plus(best.length()).toString()), minutes, suggestion, null);
    }

    private record Candidate(long roomId, String roomName, OffsetDateTime start, Duration length) {
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
        ZonedDateTime floor = t.withSecond(0).withNano(0).withMinute(t.getMinute() < 30 ? 0 : 30);
        return floor.isBefore(t) ? floor.plusMinutes(30) : floor;
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
        for (JsonNode c : node.path("classes")) {
            classes.add(new ClassItem(c.path("name").asString(""), hhmm(c.path("startAt").asString(null)),
                    hhmm(c.path("endAt").asString(null))));
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
            if (!ACTIVE.contains(r.path("status").asString(""))) {
                continue;
            }
            OffsetDateTime start = parse(r.path("startAt").asString(null));
            OffsetDateTime end = parse(r.path("endAt").asString(null));
            if (end != null && !end.toInstant().isAfter(now.toInstant())) {
                continue; // core 상태 갱신이 늦어도 끝난 예약은 안내하지 않는다
            }
            // 오늘 시작했거나, 전날 시작해 지금도 이어지는(자정 넘김) 예약은 오늘 예약
            boolean today = start != null && (start.atZoneSameInstant(SEOUL).toLocalDate().equals(now.toLocalDate())
                    || !start.toInstant().isAfter(now.toInstant()));
            items.add(new ReservationItem(r.path("id").asLong(0), r.path("roomName").asString(""),
                    r.path("startAt").asString(null), r.path("endAt").asString(null), r.path("status").asString(""),
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
                names.add(r.path("roomName").asString(""));
                if (earliestLimit == null || latest.compareTo(earliestLimit) < 0) {
                    earliestLimit = latest;
                }
            }
        }
        return new Rooms(names, hhmm(earliestLimit), null);
    }

    static String text(ZonedDateTime now, Schedule schedule, AfterClass afterClass, Reservations reservations, Rooms rooms) {
        StringBuilder sb = new StringBuilder();
        sb.append(now.getMonthValue()).append("월 ").append(now.getDayOfMonth()).append("일 ").append(dayName(now))
                .append("요일 브리핑이에요. ");
        // 수업
        if (schedule.classes() == null) {
            sb.append(schedule.note()).append(' ');
        } else if (schedule.classes().isEmpty()) {
            sb.append(schedule.note() != null ? schedule.note() : "오늘은 수업이 없어요.").append(' ');
        } else {
            sb.append("오늘 수업은 ");
            for (int i = 0; i < schedule.classes().size(); i++) {
                ClassItem c = schedule.classes().get(i);
                sb.append(i > 0 ? ", " : "").append(c.name()).append(' ').append(c.start()).append('–').append(c.end());
            }
            sb.append(' ').append(count(schedule.classes().size())).append("예요. 마지막 수업은 ").append(schedule.lastClassEnd())
                    .append("에 끝나요. ");
        }
        // 수업 끝나고 쓸 수 있는 칸
        if (afterClass != null) {
            if (afterClass.note() != null) {
                sb.append(afterClass.note()).append(' ');
            } else {
                sb.append("수업 끝나고 ").append(afterClass.start()).append("부터 ").append(afterClass.roomName()).append("을 ")
                        .append(lengthText(afterClass.minutes())).append(" 쓸 수 있어요. ");
            }
        }
        // 예약
        if (reservations.items() == null) {
            sb.append(reservations.note()).append(' ');
        } else if (reservations.items().isEmpty()) {
            sb.append("잡아 둔 예약은 없어요. ");
        } else {
            ReservationItem r = reservations.items().getFirst();
            String when = r.today() ? "오늘 " : "다음 예약은 " + monthDay(r.startAt()) + " ";
            sb.append(r.today() ? "오늘 예약은 " : when).append(r.roomName()).append(' ').append(hhmm(r.startAt())).append('–')
                    .append(hhmm(r.endAt()));
            if ("IN_USE".equals(r.status())) {
                sb.append("(사용 중)");
            }
            sb.append(r.owner() ? "이고 대표자예요. " : "이에요. ");
            if (reservations.items().size() > 1) {
                sb.append("그 뒤로 ").append(reservations.items().size() - 1).append("건이 더 있어요. ");
            }
        }
        // 빈 방
        if (rooms.availableNames() == null) {
            sb.append(rooms.note());
        } else if (rooms.availableNames().isEmpty()) {
            sb.append("지금 바로 쓸 수 있는 방은 없어요.");
        } else {
            sb.append("지금 바로 쓸 수 있는 방은 ").append(String.join(", ", rooms.availableNames())).append(' ')
                    .append(count(rooms.availableNames().size())).append("예요.");
        }
        return sb.toString().trim();
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
    public record Briefing(String date, String dayOfWeek, String generatedAt, Schedule schedule, AfterClass afterClass,
                           Reservations reservations, Rooms rooms, String text, List<String> hints, List<String> suggestions) {
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
