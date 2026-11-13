package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * cafeteria_crowd 도구 결과.
 *
 * @param code         데이터가 연결되지 않았으면 NOT_CONNECTED
 * @param cafeterias   식당별 혼잡도. 연결 전에는 빈 목록
 * @param measuredAt   잰 시각
 * @param note         학생에게 그대로 전할 문장
 */
public record CafeteriaCrowd(String code, List<Place> cafeterias, OffsetDateTime measuredAt, String note) {

    /** @param level 여유 · 보통 · 붐빔 */
    public record Place(String name, String level) {
    }
}
