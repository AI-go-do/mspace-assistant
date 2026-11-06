package kr.ac.chosun.mspace.assistant.mcp.room.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.room.application.AvailableRooms.Alternative;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.room.application.AvailableRooms.FreeRange;
import kr.ac.chosun.mspace.assistant.mcp.room.application.AvailableRooms.FreeRoom;
import kr.ac.chosun.mspace.assistant.mcp.room.application.RoomDay.RoomSlots;
import kr.ac.chosun.mspace.assistant.mcp.room.application.RoomDay.Slot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 빈방 찾기. 코어의 하루치 칸을 이어진 빈 구간으로 합치고, 시간·인원 조건으로 고른다.
 * 최종 판단은 예약할 때 코어 DB 제약이 한다. 여기 결과는 보여 주기용이다.
 */
@Service
public class FindAvailableRooms {

    static final String MIDNIGHT_NOTE = "자정 이후 부분은 예약할 때 확인돼요.";
    static final String NONE_NOTE = "조건에 맞는 빈방이 없어요. 다른 시간이나 날짜를 찾아볼 수 있어요.";
    /** 인원 때문에 방이 하나도 안 남았을 때. 빈 시간 문제로 잘못 전해지지 않게 이유와 방별 인원을 적는다 (11/5 시연: 2명 → "빈방 없음"으로 오해) */
    static final String HEADCOUNT_NOTE = "%d명이 쓸 수 있는 방이 없어요. %s %s";
    static final String TOO_FEW = "함께 쓸 사람을 더 넣어야 해요.";
    /** 원하는 시간은 찼지만 같은 날 가까운 시간이 있을 때. 모델이 alternatives를 그대로 제안하게 */
    static final String ALTERNATIVES_NOTE = "그 시간에는 빈방이 없어요. 같은 길이로 가장 가까운 시간은 alternatives에 있어요. 이 중에서 고르게 제안해요.";
    static final int MAX_ALTERNATIVES = 3;
    static final String TOO_MANY = "한 방에 들어갈 수 없는 인원이라 인원을 줄이거나 두 방으로 나눠야 해요.";

    private final RoomAvailabilityPort port;
    private final Clock clock;

    @Autowired
    public FindAvailableRooms(RoomAvailabilityPort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    public FindAvailableRooms(RoomAvailabilityPort port) {
        this(port, Clock.systemUTC());
    }

    /**
     * @param from      이 시각부터 비어 있어야 함 (없으면 하루 전체)
     * @param to        이 시각까지 비어 있어야 함. from보다 이르거나 같으면 자정을 넘긴 것으로 본다
     * @param headcount 사람 수. 방 인원 범위(minPeople~maxPeople)에 맞는 방만 남긴다
     */
    public AvailableRooms find(LocalDate date, LocalTime from, LocalTime to, Integer headcount,
                               UserCredential credential) {
        // 자정 넘김(11/2 합의): 시작 날짜만 조회하고, 다음 날 부분은 예약 결과(201/409)로 판단한다
        RoomDay day = port.findDay(date, credential);
        boolean crossesMidnight = from != null && to != null && !to.isAfter(from);
        OffsetDateTime windowStart = from == null ? null : at(date, from);
        OffsetDateTime windowEnd = windowEnd(date, to, crossesMidnight);

        List<FreeRoom> rooms = new ArrayList<>();
        List<Alternative> candidates = new ArrayList<>();
        boolean anyRoomFitsHeadcount = false;
        for (RoomSlots room : day.rooms()) {
            if (headcount != null && (headcount < room.minPeople() || headcount > room.maxPeople())) {
                continue;
            }
            anyRoomFitsHeadcount = true;
            List<FreeRange> free = merge(room.slots());
            if (windowStart != null && windowEnd != null) {
                if (free.stream().noneMatch(r -> !r.startAt().isAfter(windowStart) && !r.endAt().isBefore(windowEnd))) {
                    if (!crossesMidnight) {
                        candidates.addAll(nearest(room, free, windowStart, Duration.between(windowStart, windowEnd)));
                    }
                    continue;
                }
            } else if (windowStart != null) {
                free = free.stream().filter(r -> r.endAt().isAfter(windowStart)).toList();
            } else if (windowEnd != null) {
                free = free.stream().filter(r -> r.startAt().isBefore(windowEnd)).toList();
            }
            if (!free.isEmpty()) {
                rooms.add(new FreeRoom(room.roomId(), room.roomName(), room.minPeople(), room.maxPeople(), free));
            }
        }
        List<Alternative> alternatives = rooms.isEmpty() ? pick(candidates, windowStart) : List.of();
        String note;
        if (!rooms.isEmpty()) {
            note = crossesMidnight ? MIDNIGHT_NOTE : null;
        } else if (!alternatives.isEmpty()) {
            note = ALTERNATIVES_NOTE;
        } else if (headcount != null && !anyRoomFitsHeadcount && !day.rooms().isEmpty()) {
            note = headcountNote(headcount, day.rooms());
        } else {
            note = NONE_NOTE;
        }
        return new AvailableRooms(day.date(), rooms, note, alternatives);
    }

    /** 자정을 넘기면 끝은 다음 날 0시까지만 본다 (시작 날짜만 조회) */
    private static OffsetDateTime windowEnd(LocalDate date, LocalTime to, boolean crossesMidnight) {
        if (to == null) {
            return null;
        }
        return crossesMidnight ? at(date.plusDays(1), LocalTime.MIDNIGHT) : at(date, to);
    }

    /** 인원에 맞는 방이 없을 때: 방별 인원 범위와 너무 많은지·적은지 */
    private static String headcountNote(int headcount, List<RoomSlots> all) {
        boolean tooMany = headcount > all.stream().mapToInt(RoomSlots::maxPeople).max().orElse(0);
        return HEADCOUNT_NOTE.formatted(headcount, capacities(all), tooMany ? TOO_MANY : TOO_FEW);
    }

    /**
     * 한 방에서 원하는 길이가 들어가는 빈 구간마다, 원하는 시작에 가장 가까운 시작 하나.
     * 이미 지난 시각은 빼고, 구간 경계가 30분 칸이라 시작도 30분 칸에 맞는다.
     */
    private List<Alternative> nearest(RoomSlots room, List<FreeRange> free, OffsetDateTime wanted, Duration length) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Alternative> found = new ArrayList<>();
        for (FreeRange r : free) {
            OffsetDateTime earliest = r.startAt().isBefore(now) ? ceilToSlot(now, r.startAt()) : r.startAt();
            OffsetDateTime latest = r.endAt().minus(length);
            if (latest.isBefore(earliest)) {
                continue;
            }
            OffsetDateTime start = wanted.isBefore(earliest) ? earliest : earliestOf(wanted, latest);
            found.add(new Alternative(room.roomId(), room.roomName(), start, start.plus(length)));
        }
        return found;
    }

    private static OffsetDateTime earliestOf(OffsetDateTime a, OffsetDateTime b) {
        return a.isAfter(b) ? b : a;
    }

    /** 지난 시각 now를 구간 시작 기준 30분 칸으로 올린다 */
    private static OffsetDateTime ceilToSlot(OffsetDateTime now, OffsetDateTime base) {
        long minutes = Duration.between(base, now).toMinutes();
        long slots = (minutes + 29) / 30;
        OffsetDateTime t = base.plusMinutes(slots * 30);
        return t.isBefore(now) ? t.plusMinutes(30) : t;
    }

    /** 원하는 시작에서 가까운 순(같으면 이른 것, 그다음 방 번호), 같은 시작은 한 번만, 최대 3개 */
    private static List<Alternative> pick(List<Alternative> candidates, OffsetDateTime wanted) {
        if (wanted == null) {
            return List.of();
        }
        List<Alternative> sorted = candidates.stream()
                .sorted(Comparator.comparingLong((Alternative a) -> Math.abs(Duration.between(wanted, a.startAt()).toMinutes()))
                        .thenComparing(Alternative::startAt)
                        .thenComparingInt(Alternative::roomId))
                .toList();
        List<Alternative> picked = new ArrayList<>();
        for (Alternative a : sorted) {
            if (picked.stream().noneMatch(p -> p.startAt().isEqual(a.startAt()))) {
                picked.add(a);
            }
            if (picked.size() == MAX_ALTERNATIVES) {
                break;
            }
        }
        return picked;
    }

    /** "1번 방 3~5명, 5번 방 5~8명." 모델이 인원 범위를 지어내지 않고 그대로 전하게 */
    static String capacities(List<RoomSlots> rooms) {
        return rooms.stream()
                .map(r -> "%s %d~%d명".formatted(r.roomName(), r.minPeople(), r.maxPeople()))
                .collect(java.util.stream.Collectors.joining(", ")) + ".";
    }

    /** 이어진 빈 칸(앞 칸의 끝 = 다음 칸의 시작)을 하나의 구간으로 합친다 */
    static List<FreeRange> merge(List<Slot> slots) {
        List<FreeRange> ranges = new ArrayList<>();
        OffsetDateTime start = null;
        OffsetDateTime end = null;
        for (Slot slot : slots) {
            if (!slot.available()) {
                continue;
            }
            if (end != null && slot.startAt().isEqual(end)) {
                end = slot.endAt();
            } else {
                if (start != null) {
                    ranges.add(new FreeRange(start, end));
                }
                start = slot.startAt();
                end = slot.endAt();
            }
        }
        if (start != null) {
            ranges.add(new FreeRange(start, end));
        }
        return ranges;
    }

    private static OffsetDateTime at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(SeoulTime.ZONE).toOffsetDateTime();
    }
}
