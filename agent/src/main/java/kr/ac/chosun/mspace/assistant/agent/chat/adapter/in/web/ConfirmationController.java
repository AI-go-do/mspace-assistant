package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.user;
import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.unauthorized;

import kr.ac.chosun.mspace.assistant.agent.chat.application.AccessCodeGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConfirmationFollowUp;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConfirmationGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모델을 거치지 않는 카드 버튼.
 * <ul>
 *   <li>POST /api/agent/confirmations/{token}: 학생이 [확인]을 누름 → mcp-server 확인 API로 그대로 넘김.
 *       결과는 대화 맥락에 남기고(모델이 "예약됐다"를 알게) 빠른 답장 suggestions를 붙인다</li>
 *   <li>GET /api/agent/reservations/{id}/access-code: 입실 번호 카드 → mcp-server로 그대로 넘김 (대표자만)</li>
 * </ul>
 */
@RestController
class ConfirmationController {

    private final ConfirmationGateway confirmations;
    private final ConfirmationFollowUp followUp;
    private final AccessCodeGateway accessCodes;
    private final AgentProperties props;

    ConfirmationController(ConfirmationGateway confirmations, ConfirmationFollowUp followUp, AccessCodeGateway accessCodes,
                           AgentProperties props) {
        this.confirmations = confirmations;
        this.followUp = followUp;
        this.accessCodes = accessCodes;
        this.props = props;
    }

    /** 확인 결과(입실 번호 포함)는 학생 화면으로만 간다. 모델에는 넘기지 않는다 */
    @PostMapping("/api/agent/confirmations/{token}")
    ResponseEntity<String> confirm(@PathVariable String token, @RequestHeader HttpHeaders headers,
                                   @RequestBody(required = false) String body) {
        UserHeaders user = user(props, headers);
        ConfirmationGateway.Relayed relayed = confirmations.confirm(token, user, body);
        // 예약 생성 결과에는 입실 번호가 들어 있어 브라우저·프록시가 저장하지 않게 한다
        return ResponseEntity.status(relayed.status()).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(followUp.remember(user, relayed));
    }

    /** 입실 번호는 학생 화면으로만 간다. 캐시에 남지 않게 no-store */
    @GetMapping("/api/agent/reservations/{reservationId}/access-code")
    ResponseEntity<Object> accessCode(@PathVariable long reservationId, @RequestHeader HttpHeaders headers) {
        UserHeaders user = user(props, headers);
        if (user.isEmpty()) {
            return unauthorized();
        }
        ConfirmationGateway.Relayed relayed = accessCodes.accessCode(reservationId, user);
        return ResponseEntity.status(relayed.status()).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(relayed.json());
    }
}
