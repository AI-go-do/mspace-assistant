package kr.ac.chosun.mspace.assistant.mcp.reservation.application;

import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;

/** 예약 코어의 예약 읽기 API. 구현은 adapter/out/core */
public interface ReservationQueryPort {

    /** GET /api/reservations/me — 내가 대표자이거나 참여자인 예약, 끝나지 않은 것이 위 */
    List<MyReservation> findMine(UserCredential credential);
}
