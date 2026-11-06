package kr.ac.chosun.mspace.assistant.mcp.confirm.application;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 쓰기 도구 1단계 결과. 모델은 summary를 학생에게 보여 주고, 화면은 confirmToken으로 [확인] 버튼을 만든다.
 * 실행은 학생이 버튼을 눌렀을 때 확인 API(POST /api/confirmations/{token})로만 일어난다. 모델은 그 API를 부를 수 없다.
 *
 * @param needsParticipants 확인 카드에서 받아야 할 참여자 학번 수 (예약 생성만, 나머지는 0)
 */
public record Proposal(String confirmToken, OffsetDateTime expiresAt, String summary, List<String> notes,
                       int needsParticipants) {
}
