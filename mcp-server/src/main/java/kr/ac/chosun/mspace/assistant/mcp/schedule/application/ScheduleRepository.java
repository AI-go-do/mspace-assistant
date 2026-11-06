package kr.ac.chosun.mspace.assistant.mcp.schedule.application;

import java.util.Optional;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;

/**
 * 시간표 저장소 포트. 메모리(기본, 로컬·테스트) 또는 Redis(SCHEDULE_STORE=redis).
 * 학교가 시간표 API를 주거나 core 테이블(기획서 안 A)로 옮기면 어댑터만 바꾼다.
 * 닿지 못하면 {@link Unavailable}: 시간표가 없는 척하면 "시간표를 먼저 넣어 주세요"로 잘못 안내하게 된다.
 */
public interface ScheduleRepository {

    Optional<Timetable> find(String studentKey);

    void save(String studentKey, Timetable timetable);

    void delete(String studentKey);

    /** 저장소(Redis)에 닿지 못함 → 503 SCHEDULE_UNAVAILABLE */
    class Unavailable extends CoreApiException {
        public Unavailable() {
            super("SCHEDULE_UNAVAILABLE", "시간표를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.", 503);
        }
    }
}
