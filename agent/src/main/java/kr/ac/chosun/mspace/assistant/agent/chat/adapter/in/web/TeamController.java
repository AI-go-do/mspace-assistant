package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.error;
import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.loginRequired;
import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.MemberKey;
import kr.ac.chosun.mspace.assistant.agent.chat.application.Team;
import kr.ac.chosun.mspace.assistant.agent.chat.application.TeamStore;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 팀 (FR-AGT-105, 11/9 회의 "팀 A랑 내일 3시"). 화면만 고치고 모델은 못 고친다(멤버 학번이 모델로 가지 않게).
 * <ul>
 *   <li>GET /api/agent/teams → {teams:[{name, members:[{studentId, name}]}]}</li>
 *   <li>PUT /api/agent/teams/{name} {members:[...]} → 같은 이름이면 바꾸고, 없으면 끝에 붙인다</li>
 *   <li>DELETE /api/agent/teams/{name} → 204 (없어도 204)</li>
 * </ul>
 * 친구인지는 여기서 보지 않는다. 예약할 때 core가 친구가 아니면 403 PARTICIPANT_NOT_FRIEND로 막는다
 */
@RestController
class TeamController {

    private final TeamStore teams;
    private final AgentProperties props;

    TeamController(TeamStore teams, AgentProperties props) {
        this.teams = teams;
        this.props = props;
    }

    @GetMapping("/api/agent/teams")
    ResponseEntity<Object> list(@RequestHeader HttpHeaders headers) {
        UserHeaders user = user(props, headers);
        if (user.isEmpty()) {
            return loginRequired();
        }
        return ok(teams.load(MemberKey.of(user)));
    }

    @PutMapping("/api/agent/teams/{name}")
    ResponseEntity<Object> put(@RequestHeader HttpHeaders headers, @PathVariable String name,
                               @RequestBody(required = false) TeamRequest request) {
        UserHeaders user = user(props, headers);
        if (user.isEmpty()) {
            return loginRequired();
        }
        Team team = new Team(name, request == null ? null : request.members());
        var problem = team.problem();
        if (problem.isPresent()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_TEAM", problem.get());
        }
        Team clean = team.normalized();
        String key = MemberKey.of(user);
        List<Team> next = new ArrayList<>(teams.load(key));
        int at = indexOf(next, clean.name());
        if (at >= 0) {
            next.set(at, clean);
        } else if (next.size() >= Team.MAX_TEAMS) {
            return error(HttpStatus.BAD_REQUEST, "TOO_MANY_TEAMS", "팀은 %d개까지 만들 수 있어요. 안 쓰는 팀을 지워 주세요.".formatted(Team.MAX_TEAMS));
        } else {
            next.add(clean);
        }
        teams.save(key, next);
        return ok(next);
    }

    @DeleteMapping("/api/agent/teams/{name}")
    ResponseEntity<Object> delete(@RequestHeader HttpHeaders headers, @PathVariable String name) {
        UserHeaders user = user(props, headers);
        if (user.isEmpty()) {
            return loginRequired();
        }
        String key = MemberKey.of(user);
        List<Team> next = new ArrayList<>(teams.load(key));
        int at = indexOf(next, name == null ? "" : name.strip());
        if (at >= 0) {
            next.remove(at);
            teams.save(key, next);
        }
        return ResponseEntity.noContent().build();
    }

    private static int indexOf(List<Team> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static ResponseEntity<Object> ok(List<Team> list) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("teams", list));
    }

    record TeamRequest(List<Team.Member> members) {
    }
}
