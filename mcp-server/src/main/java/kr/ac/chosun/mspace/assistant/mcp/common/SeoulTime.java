package kr.ac.chosun.mspace.assistant.mcp.common;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Jackson은 읽을 때 OffsetDateTime을 UTC로 바꿔 둔다(14:00+09:00 → 05:00Z).
 * 그대로 모델에 주면 "5시 예약"이라고 답하므로 서울 시간으로 되돌린다 (docs/트러블슈팅.md TS-01).
 */
public final class SeoulTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private SeoulTime() {
    }

    public static OffsetDateTime of(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE).toOffsetDateTime();
    }
}
