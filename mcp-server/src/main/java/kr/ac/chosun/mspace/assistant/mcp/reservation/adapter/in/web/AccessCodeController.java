package kr.ac.chosun.mspace.assistant.mcp.reservation.adapter.in.web;

import kr.ac.chosun.mspace.assistant.mcp.common.adapter.in.web.ForwardedCredentials;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ShowAccessCode;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ShowAccessCode.Revealed;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 입실 번호 카드 API. MCP 도구가 아니라 일반 REST라서 모델은 부를 수 없다.
 * 학생 화면의 카드가 에이전트를 거쳐 학생 본인 헤더로 부른다. 대표자 본인에게만 번호를 준다.
 */
@RestController
class AccessCodeController {

    private final ShowAccessCode show;
    private final ForwardedCredentials credentials;

    AccessCodeController(ShowAccessCode show, ForwardedCredentials credentials) {
        this.show = show;
        this.credentials = credentials;
    }

    /** 예: GET /api/reservations/7/access-code → {reservationId, roomName, startAt, endAt, accessCode} */
    @GetMapping("/api/reservations/{reservationId}/access-code")
    ResponseEntity<Revealed> accessCode(@PathVariable long reservationId, @RequestHeader HttpHeaders headers) {
        Revealed revealed = show.reveal(credentials.require(headers), reservationId);
        // 번호가 브라우저·프록시 캐시에 남지 않게
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(revealed);
    }
}
