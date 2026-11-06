package kr.ac.chosun.mspace.assistant.mcp.reservation.application;

import java.time.OffsetDateTime;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;

/** 예약 코어의 쓰기 API. 학생 확인이 끝난 뒤에만 부른다 */
public interface ReservationCommandPort {

    /**
     * @param participantStudentIds 대표자 본인을 뺀 참여자 학번. 확인 카드에서 학생이 입력한 값
     * @param idempotencyKey        확인 토큰. 같은 키를 다시 보내면 core가 처음 예약을 200으로 돌려준다
     */
    CreatedReservation create(UserCredential credential, int roomId, OffsetDateTime startAt, OffsetDateTime endAt,
                              List<String> participantStudentIds, String idempotencyKey);

    /**
     * 지금 바로 쓰기 (FR-RSV-103, core POST /api/reservations/instant). 시작은 core가 요청 시각으로 정하고 바로 IN_USE 전 BOOKED,
     * 10분 안에 입실해야 한다. 끝은 30분 칸·최대 2시간은 core가 최종 판단한다
     *
     * @param idempotencyKey 확인 토큰. 같은 키 재전송은 core가 처음 예약을 200으로 돌려준다
     */
    CreatedReservation startNow(UserCredential credential, int roomId, OffsetDateTime endAt,
                                List<String> participantStudentIds, String idempotencyKey);

    /** 취소 (FR-RSV-007). 대표자·BOOKED·시작 전만. 어기면 core가 403/404/409로 막는다 */
    StatusChange cancel(UserCredential credential, long reservationId);

    /** 퇴실 (FR-RSV-008). IN_USE·대표자만. core가 조건부 UPDATE라 두 번 눌러도 한 번만 처리된다 */
    StatusChange checkout(UserCredential credential, long reservationId);

    /**
     * 연장하기 (FR-RSV-105, core #51). core가 한 트랜잭션으로 지금 예약을 퇴실하고 새 예약(IN_USE, 새 입실 번호)을 만든다.
     * 대표자·IN_USE·종료 10분 전–종료 전·1회만은 core가 최종 판단한다
     *
     * @param idempotencyKey 확인 토큰. 같은 키를 다시 보내면 core가 처음 연장 결과를 200으로 돌려준다
     */
    CreatedReservation continueUse(UserCredential credential, long reservationId, OffsetDateTime newEndAt,
                                   String idempotencyKey);

    record StatusChange(long id, String status) {
    }

    /**
     * @param accessCode 6자리 입실 번호. 학생 화면에만 보여 주고 모델에는 넘기지 않는다
     * @param replayed   true면 같은 멱등 키 재전송이라 core가 새로 만들지 않고 처음 예약을 돌려준 것(200)
     */
    record CreatedReservation(long id, int roomId, OffsetDateTime startAt, OffsetDateTime endAt, String status,
                              int headcount, String accessCode, boolean replayed) {
    }
}
