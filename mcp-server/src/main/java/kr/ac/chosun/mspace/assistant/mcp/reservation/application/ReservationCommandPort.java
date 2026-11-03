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
     * @param accessCode 6자리 입실 번호. 학생 화면에만 보여 주고 모델에는 넘기지 않는다
     * @param replayed   true면 같은 멱등 키 재전송이라 core가 새로 만들지 않고 처음 예약을 돌려준 것(200)
     */
    record CreatedReservation(long id, int roomId, OffsetDateTime startAt, OffsetDateTime endAt, String status,
                              int headcount, String accessCode, boolean replayed) {
    }
}
