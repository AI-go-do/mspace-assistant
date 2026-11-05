package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.Clock;
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
 *   <li>번호·학번·이름은 도구 결과에 없다(게이트웨이가 가림). 문장에도 방·시각만 들어간다</li>
 * </ul>
 */
@Service
public class DailyBriefing {

    private static final Logger log = LoggerFactory.getLogger(DailyBriefing.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Set<String> ACTIVE = Set.of("BOOKED", "IN_USE");

    private final ToolGateway tools;
    private final Clock clock;
    private final JsonMapper json;

    public DailyBriefing(ToolGateway tools, Clock clock, JsonMapper json) {
        this.tools = tools;
        this.clock = clock;
        this.json = json;
    }

    public Briefing today(UserHeaders headers) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(SEOUL));
        LocalDate today = now.toLocalDate();
        List<String> hints = new ArrayList<>();
        try (ToolGateway.Session session = tools.open(headers)) {
            Schedule schedule = schedule(call(session, "my_schedule", Map.of("date", today.toString())), hints);
            Reservations reservations = reservations(call(session, "my_reservations", Map.of()), now);
            Rooms rooms = rooms(call(session, "room_status", Map.of()));
            String text = text(now, schedule, reservations, rooms);
            return new Briefing(today.toString(), dayName(now), now.toOffsetDateTime().toString(), schedule, reservations, rooms,
                    text, List.copyOf(hints));
        }
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

    static String text(ZonedDateTime now, Schedule schedule, Reservations reservations, Rooms rooms) {
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
    public record Briefing(String date, String dayOfWeek, String generatedAt, Schedule schedule, Reservations reservations,
                           Rooms rooms, String text, List<String> hints) {
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
