package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.user;
import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.error;
import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.unauthorized;

import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.DailyBriefing;
import kr.ac.chosun.mspace.assistant.agent.chat.application.MemberKey;
import kr.ac.chosun.mspace.assistant.agent.chat.application.Persona;
import kr.ac.chosun.mspace.assistant.agent.chat.application.PersonaStore;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모델 없이 바로 답하는 화면용 API.
 * <ul>
 *   <li>GET /api/agent/briefing: 오늘 브리핑(수업·수업 뒤 쓸 수 있는 칸·내 예약·지금 빈 방 + 읽어 줄 문장 + 한 번에 잡기 버튼 문장). 도구 3~4개 직접 호출</li>
 *   <li>GET·PUT /api/agent/persona: 비서 성향(조용히·알아서·잔소리) 보기·바꾸기. 학생별 저장 (설계 5.v)</li>
 * </ul>
 */
@RestController
class AssistantSettingsController {

    private final DailyBriefing briefing;
    private final PersonaStore personas;
    private final AgentProperties props;

    AssistantSettingsController(DailyBriefing briefing, PersonaStore personas, AgentProperties props) {
        this.briefing = briefing;
        this.personas = personas;
        this.props = props;
    }

    /** 오늘 브리핑. 앱을 열 때 한 번 부른다. 실패한 칸은 null + note, 나머지는 그대로 (#64 글 버전) */
    @GetMapping("/api/agent/briefing")
    ResponseEntity<Object> briefing(@RequestHeader HttpHeaders headers) {
        UserHeaders user = user(props, headers);
        if (user.isEmpty()) {
            return unauthorized();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(briefing.today(user));
    }

    /** 비서 성향 보기. 고른 적 없으면 기본(알아서) */
    @GetMapping("/api/agent/persona")
    ResponseEntity<Object> persona(@RequestHeader HttpHeaders headers) {
        UserHeaders user = user(props, headers);
        if (user.isEmpty()) {
            return unauthorized();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PersonaView.of(personas.load(MemberKey.of(user))));
    }

    /** 비서 성향 바꾸기. 본문 {style: QUIET|ACTIVE|NAGGING}. 저장 실패는 503 MEMORY_UNAVAILABLE(바뀐 척하지 않는다) */
    @PutMapping("/api/agent/persona")
    ResponseEntity<Object> changePersona(@RequestHeader HttpHeaders headers, @RequestBody(required = false) PersonaRequest request) {
        UserHeaders user = user(props, headers);
        if (user.isEmpty()) {
            return unauthorized();
        }
        Optional<Persona> persona = Persona.parse(request == null ? null : request.style());
        if (persona.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_PERSONA", "성향은 QUIET, ACTIVE, NAGGING 중 하나예요.");
        }
        personas.save(MemberKey.of(user), persona.get());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PersonaView.of(persona.get()));
    }

    record PersonaRequest(String style) {
    }

    /** 지금 성향 + 고를 수 있는 전체 (화면이 라디오 버튼으로 그린다) */
    record PersonaView(String style, String label, String description, List<PersonaOption> options) {
        static PersonaView of(Persona p) {
            return new PersonaView(p.name(), p.label(), p.description(),
                    Persona.all().stream().map(o -> new PersonaOption(o.name(), o.label(), o.description())).toList());
        }
    }

    record PersonaOption(String style, String label, String description) {
    }
}
