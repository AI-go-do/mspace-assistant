package kr.ac.chosun.mspace.assistant.mcp.schedule.adapter.out.memory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.ScheduleRepository;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.Timetable;

/** 기본 보관(로컬·테스트, mcp-server 1대). 재시작하면 사라진다. 서버 배포는 SCHEDULE_STORE=redis */
public class InMemoryScheduleRepository implements ScheduleRepository {

    private final Map<String, Timetable> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Timetable> find(String studentKey) {
        return Optional.ofNullable(store.get(studentKey));
    }

    @Override
    public void save(String studentKey, Timetable timetable) {
        store.put(studentKey, timetable);
    }

    @Override
    public void delete(String studentKey) {
        store.remove(studentKey);
    }
}
