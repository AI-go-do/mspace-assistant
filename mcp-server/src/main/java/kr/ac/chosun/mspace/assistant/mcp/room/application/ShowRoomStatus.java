package kr.ac.chosun.mspace.assistant.mcp.room.application;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.confirm.application.ContinueProperties;
import org.springframework.stereotype.Service;

/**
 * 지금 방 현황. 코어 현황에 "바로 쓰기를 시작하면 언제까지 고를 수 있나"(latestEndAt)를 더한다.
 * 계산 규칙은 core ReservationTime.instant와 같다: 끝은 30분 칸, 지금 + 최대(2시간)를 칸으로 올린 시각까지, 다음 예약 전까지.
 * 모델이 시간을 세지 않게 코드가 돌려준다.
 */
@Service
public class ShowRoomStatus {

    static final String NONE_NOTE = "지금 바로 쓸 수 있는 방이 없어요. 가장 빨리 비는 시각을 알려 주거나, find_available_rooms로 다른 시간을 찾아볼 수 있어요.";
    static final String AVAILABLE_NOTE = "지금 바로 쓰려면 start_now_reservation으로 제안해요. 입실은 시작 10분 안에 해야 해요.";

    private final RoomStatusPort port;
    private final ContinueProperties rules;
    private final Clock clock;

    public ShowRoomStatus(RoomStatusPort port, ContinueProperties rules, Clock clock) {
        this.port = port;
        this.rules = rules;
        this.clock = clock;
    }

    public RoomsNow show(UserCredential credential) {
        RoomsNow core = port.status(credential);
        OffsetDateTime now = core.now() != null ? core.now() : clock.instant().atZone(SeoulTime.ZONE).toOffsetDateTime();
        OffsetDateTime latestByRule = ceilToSlot(now.plusMinutes(rules.maxMinutes()));
        List<RoomsNow.RoomNow> rooms = core.rooms().stream().map(r -> {
            OffsetDateTime latest = null;
            if ("AVAILABLE".equals(r.status())) {
                // 다음 예약 앞까지만, 그것도 30분 칸으로 내린 시각까지 (14:40 → 14:30). start_now_reservation이 칸이 아닌 끝을 거부하니까
                latest = r.nextStartAt() != null && r.nextStartAt().isBefore(latestByRule) ? floorToSlot(SeoulTime.of(r.nextStartAt())) : latestByRule;
                // 다음 예약이 너무 가까워 30분 칸 하나도 안 나오면 바로 쓸 수 없다
                if (!latest.isAfter(now)) {
                    latest = null;
                }
            }
            return new RoomsNow.RoomNow(r.roomId(), r.roomName(), r.minPeople(), r.maxPeople(), r.status(),
                    r.currentEndAt(), r.nextStartAt(), latest, r.presence());
        }).toList();
        long available = rooms.stream().filter(r -> r.latestEndAt() != null).count();
        return new RoomsNow(now, (int) available, rooms, available == 0 ? NONE_NOTE : AVAILABLE_NOTE);
    }

    /** 14:40 → 14:30, 14:30 → 14:30 */
    OffsetDateTime floorToSlot(OffsetDateTime t) {
        long slot = Duration.ofMinutes(rules.slotMinutes()).toSeconds();
        long rem = Math.floorMod(t.toEpochSecond(), slot);
        return t.truncatedTo(ChronoUnit.SECONDS).minusSeconds(rem);
    }

    /** 14:17 → 14:30, 14:30 → 14:30 */
    OffsetDateTime ceilToSlot(OffsetDateTime t) {
        long slot = Duration.ofMinutes(rules.slotMinutes()).toSeconds();
        long s = t.toEpochSecond();
        long rem = Math.floorMod(s, slot);
        OffsetDateTime floored = t.truncatedTo(ChronoUnit.SECONDS).minusSeconds(rem);
        return (rem == 0 && t.getNano() == 0 ? floored : floored.plusSeconds(slot));
    }
}
