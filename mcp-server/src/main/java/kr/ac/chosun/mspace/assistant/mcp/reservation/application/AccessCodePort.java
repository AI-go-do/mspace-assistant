package kr.ac.chosun.mspace.assistant.mcp.reservation.application;

import java.util.Optional;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;

/**
 * 입실 번호 읽기. 화면 카드(REST)에서만 쓴다. MCP 도구는 이 포트를 쓰지 않는다: 도구 결과는 모델 입력이 되기 때문이다.
 * 구현은 adapter/out/core (core GET /api/reservations/me를 학생 본인 헤더로 다시 부른다).
 */
public interface AccessCodePort {

    /** 내 예약 중 reservationId 한 건과 그 입실 번호. core는 대표자가 아니면 번호를 null로 준다 */
    Optional<WithAccessCode> findWithAccessCode(UserCredential credential, long reservationId);

    /** @param accessCode 6자리. 대표자가 아니거나 core가 안 줬으면 null */
    record WithAccessCode(MyReservation reservation, String accessCode) {

        /** 로그·예외 메시지에 번호가 찍히지 않게 */
        @Override
        public String toString() {
            return "WithAccessCode[id=" + reservation.id() + "]";
        }
    }
}
