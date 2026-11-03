package kr.ac.chosun.mspace.assistant.mcp.reservation.application;

import java.time.OffsetDateTime;

/**
 * 내 예약 한 건. 예약 코어 응답에서 모델에게 보여 줘도 되는 값만 남긴 모양이다.
 * 6자리 입실 번호(accessCode)와 참여자 이름은 이 모양에 없다. 읽지도, 넘기지도 않는다 (CLAUDE.md 4장).
 *
 * @param owner            내가 대표자인지 (취소·퇴실은 대표자만 된다)
 * @param participantCount 대표자 포함 인원
 */
public record MyReservation(long id, String roomName, OffsetDateTime startAt, OffsetDateTime endAt,
                            String status, boolean owner, int participantCount) {
}
