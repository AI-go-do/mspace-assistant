package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kr.ac.chosun.mspace.assistant.agent.chat.application.Team;
import kr.ac.chosun.mspace.assistant.agent.chat.application.TeamStore;

/** 메모리 보관 (기본, agent 1대. 재시작하면 사라짐) */
public class InMemoryTeamStore implements TeamStore {

    private final Map<String, List<Team>> teams = new ConcurrentHashMap<>();

    @Override
    public List<Team> load(String memberKey) {
        return teams.getOrDefault(memberKey, List.of());
    }

    @Override
    public void save(String memberKey, List<Team> list) {
        teams.put(memberKey, List.copyOf(list));
    }
}
