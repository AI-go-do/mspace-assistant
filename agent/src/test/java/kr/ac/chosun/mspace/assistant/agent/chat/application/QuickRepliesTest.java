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
    @DisplayName("주간 계획(week_plan): 첫 번째 쓸 수 있는 날을 '날짜 시각 방 잡아 줘'로, 없으면 길이 줄이기")
    void weekPlan() {
        // given
        List<Seen> some = List.of(ok("week_plan", "{\"days\":[{\"date\":\"2026-11-05\",\"option\":null,\"reason\":\"빈 시간 없음\"},"
                + "{\"date\":\"2026-11-06\",\"option\":{\"roomId\":2,\"roomName\":\"2번 방\",\"startAt\":\"2026-11-06T15:30:00+09:00\",\"endAt\":\"2026-11-06T17:30:00+09:00\"}}]}"));
        List<Seen> none = List.of(ok("week_plan", "{\"days\":[{\"date\":\"2026-11-05\",\"option\":null}]}"));

        // when
        List<String> a = QuickReplies.suggest(some, false);
        List<String> b = QuickReplies.suggest(none, false);

        // then
        assertThat(a).containsExactly("11월 6일 15:30 2번 방 잡아 줘", "다른 날도 볼게");
        assertThat(b).containsExactly("1시간으로 다시 볼게", "내 예약 보여 줘");
    }

    @Test
    @DisplayName("빈방 없음 + 서버 대안(alternatives): 앞의 두 개를 '시각 방 잡아 줘' 버튼으로")
    void roomsAlternatives() {
        // given
        List<Seen> seen = List.of(ok("find_available_rooms", "{\"date\":\"2026-11-06\",\"rooms\":[],\"alternatives\":["
                + "{\"roomId\":2,\"roomName\":\"2번 방\",\"startAt\":\"2026-11-06T15:00:00+09:00\",\"endAt\":\"2026-11-06T17:00:00+09:00\"},"
                + "{\"roomId\":1,\"roomName\":\"1번 방\",\"startAt\":\"2026-11-06T11:00:00+09:00\",\"endAt\":\"2026-11-06T13:00:00+09:00\"},"
                + "{\"roomId\":1,\"roomName\":\"1번 방\",\"startAt\":\"2026-11-06T17:00:00+09:00\",\"endAt\":\"2026-11-06T19:00:00+09:00\"}]}"));

        // when
        List<String> got = QuickReplies.suggest(seen, false);

        // then
        assertThat(got).containsExactly("15:00 2번 방 잡아 줘", "11:00 1번 방 잡아 줘", "다른 시간도 볼게");
    }

    @Test
    @DisplayName("빈방 없음: 인원 때문이면 날짜 대신 인원을 바꾸게, 빈 결과는 버튼 없음")
    void roomsHeadcountAndEmpty() {
        // given
        List<Seen> headcount = List.of(ok("find_available_rooms",
                "{\"date\":\"2026-11-06\",\"rooms\":[],\"note\":\"2명이 쓸 수 있는 방이 없어요. 1번 방 3~5명. 함께 쓸 사람을 더 넣어야 해요.\"}"));
        List<Seen> blank = List.of(ok("find_available_rooms", ""));

        // when
        List<String> a = QuickReplies.suggest(headcount, false);
        List<String> b = QuickReplies.suggest(blank, false);

        // then
        assertThat(a).containsExactly("인원 바꿔서 다시 볼게", "지금 빈 방 있어?");
        assertThat(b).isEmpty();
    }

    @Test
    @DisplayName("빈방 없음: 다음 날이 조회 범위(오늘~+6일) 밖이면 '같은 시간 봐 줘'를 안 띄운다")
    void roomsLastDay() {
        // given: 11/3 기준 마지막 조회일 11/9
        java.time.LocalDate lastAllowed = java.time.LocalDate.of(2026, 11, 9);
        List<Seen> lastDay = List.of(ok("find_available_rooms", "{\"date\":\"2026-11-09\",\"rooms\":[]}"));
        List<Seen> dayBefore = List.of(ok("find_available_rooms", "{\"date\":\"2026-11-08\",\"rooms\":[]}"));
        List<Seen> noDate = List.of(ok("find_available_rooms", "{\"rooms\":[]}"));

        // when
        List<String> a = QuickReplies.suggest(lastDay, false, lastAllowed);
        List<String> b = QuickReplies.suggest(dayBefore, false, lastAllowed);
        List<String> c = QuickReplies.suggest(noDate, false, lastAllowed);

        // then
        assertThat(a).containsExactly("지금 빈 방 있어?");
        assertThat(b).containsExactly("11월 9일 같은 시간 봐 줘", "지금 빈 방 있어?");
        assertThat(c).containsExactly("지금 빈 방 있어?");
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
    @DisplayName("내 예약: 사용 중이면 연장·퇴실, 예약만 있으면 입실 번호·취소, 끝낸 예약만 있으면 '지난번처럼', 없으면 빈방 찾기")
    void reservations() {
        // given
        List<Seen> inUse = List.of(ok("my_reservations", "{\"reservations\":[{\"status\":\"IN_USE\"},{\"status\":\"BOOKED\"}]}"));
        List<Seen> booked = List.of(ok("my_reservations", "{\"reservations\":[{\"status\":\"BOOKED\"}]}"));
        List<Seen> empty = List.of(ok("my_reservations", "{\"reservations\":[]}"));
        List<Seen> finished = List.of(ok("my_reservations", "{\"reservations\":[{\"status\":\"CHECKED_OUT\"},{\"status\":\"CANCELED\"}]}"));
        List<Seen> canceledOnly = List.of(ok("my_reservations", "{\"reservations\":[{\"status\":\"CANCELED\"},{\"status\":\"NO_SHOW\"}]}"));

        // when
        List<String> a = QuickReplies.suggest(inUse, false);
        List<String> b = QuickReplies.suggest(booked, false);
        List<String> c = QuickReplies.suggest(empty, false);
        List<String> d = QuickReplies.suggest(finished, false);
        List<String> e = QuickReplies.suggest(canceledOnly, false);

        // then
        assertThat(a).containsExactly("30분 연장해 줘", "퇴실할게");
        assertThat(b).containsExactly("입실 번호 보여 줘", "이 예약 취소해 줘");
        assertThat(c).containsExactly("내일 오후 2시 빈방 있어?", "지금 빈 방 있어?");
        assertThat(d).containsExactly("지난번처럼 다시 잡아 줘", "지금 빈 방 있어?");
        assertThat(e).as("취소·노쇼만 있으면 되풀이할 예약이 아니다").isEqualTo(c);
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
