package kr.ac.chosun.mspace.assistant.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.chosun.mspace.assistant.agent.chat.application.QuickReplies.Seen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 빠른 답장: 마지막 도구 결과 → 버튼 글. 모델 없음 */
class QuickRepliesTest {

    static Seen ok(String tool, String json) {
        return new Seen(tool, json, false);
    }

    @Test
    @DisplayName("도구를 안 불렀으면 시작 질문 3개")
    void start() {
        assertThat(QuickReplies.suggest(List.of(), false)).isEqualTo(QuickReplies.START).hasSize(3);
    }

    @Test
    @DisplayName("확인 카드가 있으면 비어 있다 (카드 버튼이 다음 행동)")
    void cards() {
        assertThat(QuickReplies.suggest(List.of(ok("find_available_rooms", "{\"rooms\":[{\"roomId\":1}]}")), true)).isEmpty();
    }

    @Test
    @DisplayName("빈방 결과: 방이 있으면 '잡아 줘', 없으면 '내일 같은 시간'")
    void rooms() {
        assertThat(QuickReplies.suggest(List.of(ok("find_available_rooms", "{\"rooms\":[{\"roomId\":1}]}")), false))
                .containsExactly("첫 번째로 나온 방 잡아 줘", "다른 시간도 볼게");
        assertThat(QuickReplies.suggest(List.of(ok("find_available_rooms", "{\"rooms\":[]}")), false))
                .containsExactly("내일 같은 시간 봐 줘", "지금 빈 방 있어?");
    }

    @Test
    @DisplayName("내 예약: 사용 중이면 연장·퇴실, 예약만 있으면 입실 번호·취소, 없으면 빈방 찾기")
    void reservations() {
        assertThat(QuickReplies.suggest(List.of(ok("my_reservations", "{\"reservations\":[{\"status\":\"IN_USE\"},{\"status\":\"BOOKED\"}]}")), false))
                .containsExactly("30분 연장해 줘", "퇴실할게");
        assertThat(QuickReplies.suggest(List.of(ok("my_reservations", "{\"reservations\":[{\"status\":\"BOOKED\"}]}")), false))
                .containsExactly("입실 번호 보여 줘", "이 예약 취소해 줘");
        assertThat(QuickReplies.suggest(List.of(ok("my_reservations", "{\"reservations\":[]}")), false))
                .containsExactly("내일 오후 2시 빈방 있어?", "지금 빈 방 있어?");
    }

    @Test
    @DisplayName("시간표: NO_SCHEDULE이면 비움(화면 버튼), 수업 있으면 '수업 끝나고'")
    void schedule() {
        assertThat(QuickReplies.suggest(List.of(ok("my_schedule", "{\"code\":\"NO_SCHEDULE\",\"classes\":[]}")), false)).isEmpty();
        assertThat(QuickReplies.suggest(List.of(ok("my_schedule", "{\"code\":null,\"classes\":[{\"name\":\"OS\"}]}")), false))
                .containsExactly("수업 끝나고 2시간 잡아 줘");
    }

    @Test
    @DisplayName("마지막 '성공한' 도구 기준. 실패 결과나 깨진 JSON은 건너뛴다")
    void lastSuccessful() {
        var seen = List.of(ok("room_status", "{\"availableCount\":2}"), new Seen("find_available_rooms", "{\"message\":\"실패\"}", true));
        assertThat(QuickReplies.suggest(seen, false)).containsExactly("지금 바로 1시간 쓸게", "내 예약 보여 줘");
        assertThat(QuickReplies.suggest(List.of(ok("room_status", "not json")), false)).isEmpty();
        assertThat(QuickReplies.suggest(List.of(new Seen("room_status", "{}", true)), false)).isEmpty();
    }
}
