package kr.ac.chosun.mspace.assistant.agent.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ChatResult;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ToolGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모델 평가용 가짜 도구. 도구 목록은 진짜 mcp-server의 tools/list 스냅숏(eval/tools.json)이고, 결과는 고정된 가짜 데이터다.
 * core·Redis·로그인 토큰 없이 모델의 판단(어느 도구를 어떤 인자로)만 잰다. 쓰기 도구는 진짜처럼 확인 카드만 만든다.
 */
final class FixtureTools implements ToolGateway {

    static final ZoneOffset KST = ZoneOffset.ofHours(9);
    static final Set<String> WRITE_TOOLS = Set.of("create_reservation", "start_now_reservation", "cancel_reservation",
            "checkout_reservation", "continue_reservation");

    private final List<ToolSpec> specs;
    private final OffsetDateTime now;
    private final Map<String, Object> overrides;
    private final JsonMapper json;

    FixtureTools(List<ToolSpec> specs, OffsetDateTime now, Map<String, Object> overrides, JsonMapper json) {
        this.specs = specs;
        this.now = now;
        this.overrides = overrides == null ? Map.of() : overrides;
        this.json = json;
    }

    @SuppressWarnings("unchecked")
    static List<ToolSpec> loadSpecs(JsonMapper json) {
        try (InputStream in = FixtureTools.class.getResourceAsStream("/eval/tools.json")) {
            Map<String, Object> root = json.readValue(in, Map.class);
            List<ToolSpec> out = new ArrayList<>();
            for (Map<String, Object> t : (List<Map<String, Object>>) root.get("tools")) {
                out.add(new ToolSpec((String) t.get("name"), (String) t.get("description"),
                        (Map<String, Object>) t.get("inputSchema")));
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Session open(UserHeaders headers) {
        return new Session() {
            @Override
            public List<ToolSpec> tools() {
                return specs;
            }

            @Override
            public Outcome call(String name, Map<String, Object> arguments) {
                return FixtureTools.this.call(name, arguments == null ? Map.of() : arguments);
            }

            @Override
            public void close() {
                // 붙은 서버가 없어 닫을 것이 없다
            }
        };
    }

    Outcome call(String name, Map<String, Object> args) {
        if (specs.stream().noneMatch(s -> s.name().equals(name))) {
            return error("UNKNOWN_TOOL", "없는 도구예요.");
        }
        if (overrides.containsKey(name)) {
            return ok(overrides.get(name));
        }
        return switch (name) {
            case "find_available_rooms" -> findRooms(args);
            case "room_status" -> ok(roomStatus());
            case "my_reservations" -> ok(Map.of("reservations", List.of()));
            case "my_schedule" -> ok(schedule(date(args.get("date")).orElse(today())));
            case "week_plan" -> ok(weekPlan(intArg(args.get("minutes")).orElse(120)));
            case "usage_rules" -> ok(rules());
            case "show_access_code" -> new Outcome(write(Map.of("owner", true, "reservationId", 101,
                    "note", "입실 번호는 화면 카드로 보여 드려요.")), false, Optional.empty(),
                    Optional.of(new ChatResult.AccessCodeCard(101)));
            default -> propose(name, args);
        };
    }

    LocalDate today() {
        return now.toLocalDate();
    }

    // ---- 읽기 도구 ----

    private Outcome findRooms(Map<String, Object> args) {
        Optional<LocalDate> date = date(args.get("date"));
        if (date.isEmpty()) {
            return error("INVALID_ARGUMENT", "date는 YYYY-MM-DD로 넣어 주세요.");
        }
        if (date.get().isBefore(today()) || date.get().isAfter(today().plusDays(6))) {
            return error("OUT_OF_RANGE", "오늘부터 7일 안의 날짜만 볼 수 있어요.");
        }
        Optional<Integer> headcount = intArg(args.get("headcount"));
        LocalTime from = time(args.get("from")).orElse(LocalTime.of(9, 0));
        LocalTime to = time(args.get("to")).orElse(from);
        List<Map<String, Object>> rooms = new ArrayList<>();
        for (int id = 1; id <= 5; id++) {
            int min = id == 5 ? 5 : 3;
            int max = id == 5 ? 8 : 5;
            if (headcount.isPresent() && (headcount.get() < min || headcount.get() > max)) {
                continue;
            }
            // 1번 방만 14:00–16:00이 차 있다. 나머지는 09:00–22:00 비어 있다
            List<LocalTime[]> free = id == 1
                    ? List.of(new LocalTime[]{LocalTime.of(9, 0), LocalTime.of(14, 0)},
                            new LocalTime[]{LocalTime.of(16, 0), LocalTime.of(22, 0)})
                    : List.<LocalTime[]>of(new LocalTime[]{LocalTime.of(9, 0), LocalTime.of(22, 0)});
            List<Map<String, Object>> ranges = new ArrayList<>();
            for (LocalTime[] r : free) {
                if (!r[0].isAfter(from) && !r[1].isBefore(to)) {
                    ranges.add(Map.of("startAt", at(date.get(), r[0]), "endAt", at(date.get(), r[1])));
                }
            }
            if (!ranges.isEmpty()) {
                rooms.add(room(id, min, max, Map.of("free", ranges)));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", date.get().toString());
        body.put("rooms", rooms);
        if (rooms.isEmpty() && headcount.isPresent()) {
            body.put("note", "인원에 맞는 방이 없어요. 1~4번 방은 3~5명, 5번 방은 5~8명이 쓸 수 있어요.");
        }
        body.put("alternatives", List.of());
        return ok(body);
    }

    private Map<String, Object> roomStatus() {
        List<Map<String, Object>> rooms = new ArrayList<>();
        for (int id = 1; id <= 5; id++) {
            boolean free = id == 2 || id == 3;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("status", free ? "AVAILABLE" : "IN_USE");
            extra.put("latestEndAt", free ? at(today(), LocalTime.of(12, 0)) : null);
            extra.put("currentEndAt", free ? null : at(today(), LocalTime.of(11, 0)));
            rooms.add(room(id, id == 5 ? 5 : 3, id == 5 ? 8 : 5, extra));
        }
        return Map.of("now", now.toString(), "availableCount", 2, "rooms", rooms);
    }

    /** 평일은 09:00–10:15, 13:30–14:45 수업. 끝난 뒤 15분 여유를 두고 15:00부터 찾는다. 주말은 수업 없음 */
    private Map<String, Object> schedule(LocalDate date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", date.toString());
        body.put("dayOfWeek", date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN));
        if (weekend(date)) {
            body.put("classes", List.of());
            body.put("note", "이날은 수업이 없어요.");
            return body;
        }
        body.put("classes", List.of(
                Map.of("name", "자료구조", "startAt", at(date, LocalTime.of(9, 0)), "endAt", at(date, LocalTime.of(10, 15))),
                Map.of("name", "운영체제", "startAt", at(date, LocalTime.of(13, 30)), "endAt", at(date, LocalTime.of(14, 45)))));
        body.put("lastClassEndAt", at(date, LocalTime.of(14, 45)));
        body.put("searchFromAt", at(date, LocalTime.of(15, 0)));
        body.put("bufferMinutes", 15);
        return body;
    }

    private Map<String, Object> weekPlan(int minutes) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = today().plusDays(i);
            LocalTime start = weekend(d) ? LocalTime.of(9, 0) : LocalTime.of(15, 0);
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", d.toString());
            day.put("dayOfWeek", d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN));
            day.put("classCount", weekend(d) ? 0 : 2);
            day.put("option", Map.of("roomId", 2, "roomName", "2번 방", "startAt", at(d, start),
                    "endAt", at(d, start.plusMinutes(minutes))));
            days.add(day);
        }
        return Map.of("from", today().toString(), "to", today().plusDays(6).toString(), "minutes", minutes, "days", days);
    }

    private static Map<String, Object> rules() {
        return Map.of("reservation", Map.of("bookingWindowDays", 7, "slotMinutes", 30, "maxMinutes", 120,
                        "checkinOpenBeforeMinutes", 10, "noShowMinutes", 15, "instantNoShowMinutes", 10,
                        "extension", Map.of("maxCount", 1, "openBeforeMinutes", 10, "maxMinutes", 120)),
                "conduct", List.of(Map.of("key", "CLEAN", "title", "정리", "rule", "퇴실 전에 쓰레기를 치워요.",
                        "penalty", "경고 3번이면 14일 정지")));
    }

    // ---- 쓰기 도구: 진짜처럼 확인안만 낸다 ----

    private Outcome propose(String name, Map<String, Object> args) {
        String summary;
        int others = 0;
        switch (name) {
            case "create_reservation" -> {
                Optional<Integer> room = intArg(args.get("roomId"));
                Optional<Integer> people = intArg(args.get("headcount"));
                if (room.isEmpty() || people.isEmpty() || args.get("startAt") == null || args.get("endAt") == null) {
                    return error("INVALID_ARGUMENT", "방·시작·끝·인원을 모두 넣어 주세요.");
                }
                summary = "%d번 방, %s–%s, %d명으로 예약할까요?".formatted(room.get(), args.get("startAt"), args.get("endAt"),
                        people.get());
                others = Math.max(0, people.get() - 1);
            }
            case "start_now_reservation" -> {
                Optional<Integer> room = intArg(args.get("roomId"));
                Optional<Integer> people = intArg(args.get("headcount"));
                if (room.isEmpty() || people.isEmpty() || args.get("endAt") == null) {
                    return error("INVALID_ARGUMENT", "방·끝 시각·인원을 모두 넣어 주세요.");
                }
                summary = "%d번 방을 지금부터 %s까지 %d명으로 바로 쓸까요?".formatted(room.get(), args.get("endAt"), people.get());
                others = Math.max(0, people.get() - 1);
            }
            case "continue_reservation" -> summary = "예약 %s 사용을 %s분 연장할까요?".formatted(args.get("reservationId"),
                    args.get("minutes"));
            case "cancel_reservation" -> summary = "예약 %s를 취소할까요?".formatted(args.get("reservationId"));
            case "checkout_reservation" -> summary = "예약 %s 사용을 끝내고 퇴실할까요?".formatted(args.get("reservationId"));
            default -> {
                return error("UNKNOWN_TOOL", "없는 도구예요.");
            }
        }
        String expiresAt = now.plusMinutes(5).toString();
        var card = new ChatResult.Confirmation(name, "eval-token", summary, List.of(), others, expiresAt);
        return new Outcome(write(Map.of("summary", summary, "notes", List.of(), "needsParticipants", others,
                "expiresAt", expiresAt)), false, Optional.of(card));
    }

    // ---- 도움 ----

    private Outcome ok(Object body) {
        return new Outcome(write(body), false);
    }

    private Outcome error(String code, String message) {
        return new Outcome(write(Map.of("code", code, "message", message)), true);
    }

    private String write(Object body) {
        return json.writeValueAsString(body);
    }

    private static Map<String, Object> room(int id, int min, int max, Map<String, Object> extra) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("roomId", id);
        r.put("roomName", id + "번 방");
        r.put("minPeople", min);
        r.put("maxPeople", max);
        r.putAll(extra);
        return r;
    }

    private static boolean weekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static String at(LocalDate d, LocalTime t) {
        return OffsetDateTime.of(d, t, KST).toString();
    }

    static Optional<LocalDate> date(Object v) {
        try {
            return v == null ? Optional.empty() : Optional.of(LocalDate.parse(v.toString().strip()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static Optional<LocalTime> time(Object v) {
        try {
            return v == null ? Optional.empty() : Optional.of(LocalTime.parse(v.toString().strip()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 모델은 숫자를 3, 3.0, "3"으로 섞어 보낸다 */
    static Optional<Integer> intArg(Object v) {
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return Optional.of(n.intValue());
        }
        try {
            return v == null ? Optional.empty() : Optional.of((int) Double.parseDouble(v.toString().strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
