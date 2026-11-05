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
        // given
        List<Seen> seen = List.of();

        // when
        List<String> got = QuickReplies.suggest(seen, false);

        // then
        assertThat(got).isEqualTo(QuickReplies.START).hasSize(3);
    }

    @Test
    @DisplayName("확인 카드가 있으면 비어 있다 (카드 버튼이 다음 행동)")
    void cards() {
        // given
        List<Seen> seen = List.of(ok("find_available_rooms", "{\"rooms\":[{\"roomId\":1}]}"));

        // when
        List<String> got = QuickReplies.suggest(seen, true);

        // then
        assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("빈방 결과: 방이 있으면 '잡아 줘', 없으면 조회한 날의 다음 날(같은 날을 다시 조회하지 않게)")
    void rooms() {
        // given
        List<Seen> found = List.of(ok("find_available_rooms", "{\"date\":\"2026-11-06\",\"rooms\":[{\"roomId\":1}]}"));
        List<Seen> none = List.of(ok("find_available_rooms", "{\"date\":\"2026-11-06\",\"rooms\":[]}"));
        List<Seen> noDate = List.of(ok("find_available_rooms", "{\"rooms\":[]}"));

        // when
        List<String> a = QuickReplies.suggest(found, false);
        List<String> b = QuickReplies.suggest(none, false);
        List<String> c = QuickReplies.suggest(noDate, false);

        // then
        assertThat(a).containsExactly("첫 번째로 나온 방 잡아 줘", "다른 시간도 볼게");
        assertThat(b).containsExactly("11월 7일 같은 시간 봐 줘", "지금 빈 방 있어?");
        assertThat(c).containsExactly("다음 날 같은 시간 봐 줘", "지금 빈 방 있어?");
    }

    @Test
    @DisplayName("내 예약: 사용 중이면 연장·퇴실, 예약만 있으면 입실 번호·취소, 없으면 빈방 찾기")
    void reservations() {
        // given
        List<Seen> inUse = List.of(ok("my_reservations", "{\"reservations\":[{\"status\":\"IN_USE\"},{\"status\":\"BOOKED\"}]}"));
        List<Seen> booked = List.of(ok("my_reservations", "{\"reservations\":[{\"status\":\"BOOKED\"}]}"));
        List<Seen> empty = List.of(ok("my_reservations", "{\"reservations\":[]}"));

        // when
        List<String> a = QuickReplies.suggest(inUse, false);
        List<String> b = QuickReplies.suggest(booked, false);
        List<String> c = QuickReplies.suggest(empty, false);

        // then
        assertThat(a).containsExactly("30분 연장해 줘", "퇴실할게");
        assertThat(b).containsExactly("입실 번호 보여 줘", "이 예약 취소해 줘");
        assertThat(c).containsExactly("내일 오후 2시 빈방 있어?", "지금 빈 방 있어?");
    }

    @Test
    @DisplayName("시간표: NO_SCHEDULE이면 비움(화면 버튼), 수업 있으면 '수업 끝나고'")
    void schedule() {
        // given
        List<Seen> none = List.of(ok("my_schedule", "{\"code\":\"NO_SCHEDULE\",\"classes\":[]}"));
        List<Seen> some = List.of(ok("my_schedule", "{\"code\":null,\"classes\":[{\"name\":\"OS\"}]}"));

        // when
        List<String> a = QuickReplies.suggest(none, false);
        List<String> b = QuickReplies.suggest(some, false);

        // then
        assertThat(a).isEmpty();
        assertThat(b).containsExactly("수업 끝나고 2시간 잡아 줘");
    }

    @Test
    @DisplayName("마지막 '성공한' 도구 기준. 실패 결과나 깨진 JSON은 건너뛴다")
    void lastSuccessful() {
        // given
        var seen = List.of(ok("room_status", "{\"availableCount\":2}"), new Seen("find_available_rooms", "{\"message\":\"실패\"}", true));
        var broken = List.of(ok("room_status", "not json"));
        var onlyFailed = List.of(new Seen("room_status", "{}", true));

        // when
        List<String> a = QuickReplies.suggest(seen, false);
        List<String> b = QuickReplies.suggest(broken, false);
        List<String> c = QuickReplies.suggest(onlyFailed, false);

        // then
        assertThat(a).containsExactly("지금 바로 1시간 쓸게", "내 예약 보여 줘");
        assertThat(b).isEmpty();
        assertThat(c).isEmpty();
    }
}
