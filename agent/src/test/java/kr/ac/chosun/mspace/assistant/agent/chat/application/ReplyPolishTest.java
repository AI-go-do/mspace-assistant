package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ReplyPolishTest {

    @Test
    @DisplayName("모델이 직접 쓴 꼬리말은 끝에서 모두 지운다 (두 번 붙어도, 시간표 꼬리말도)")
    void stripsModelWrittenFooters() {
        // given
        String twice = "2번 방이 비어 있어요\n\n(예약 시스템 15:00 조회 기준)\n\n(예약 시스템 15:00 조회 기준)";
        String both = "수업 끝나고 쓸 수 있어요 (예약 시스템 9:05 조회 · 입력한 시간표 기준)";
        String schedule = "오늘 수업은 둘이에요\n(입력한 시간표 기준)\n";

        String period = "2번 방이 비어 있어요 (예약 시스템 15:00 조회 기준).";

        // when
        String strippedTwice = ReplyPolish.stripFooters(twice);
        String strippedBoth = ReplyPolish.stripFooters(both);
        String strippedSchedule = ReplyPolish.stripFooters(schedule);
        String strippedPeriod = ReplyPolish.stripFooters(period);

        // then
        assertThat(strippedTwice).isEqualTo("2번 방이 비어 있어요");
        assertThat(strippedBoth).isEqualTo("수업 끝나고 쓸 수 있어요");
        assertThat(strippedSchedule).isEqualTo("오늘 수업은 둘이에요");
        assertThat(strippedPeriod).as("닫는 괄호 뒤 마침표").isEqualTo("2번 방이 비어 있어요");
        assertThat(ReplyPolish.stripFooters("꼬리말 없음")).isEqualTo("꼬리말 없음");
        assertThat(ReplyPolish.stripFooters("(15:00 조회 기준)이라는 말은 본문")).as("끝이 아니면 안 지움")
                .isEqualTo("(15:00 조회 기준)이라는 말은 본문");
        assertThat(ReplyPolish.stripFooters(null)).isNull();
    }

    @Test
    @DisplayName("도구 이름(snake_case)은 학생 말로 바꾸고, 백틱은 같이 지운다. 모르는 이름은 '그 기능'")
    void hidesToolNames() {
        // given
        String text = "`start_now_reservation`으로 지금 바로 잡을 수 있어요. room_status를 다시 볼까요? "
                + "make_coffee는 없어요. roomId=2, 2026-11-06, no-show는 그대로.";

        // when
        String out = ReplyPolish.hideToolNames(text);

        // then
        assertThat(out).as("조사는 바꾼 말의 받침에 맞춘다").isEqualTo("지금 바로 쓰기로 지금 바로 잡을 수 있어요. 지금 빈 방을 다시 볼까요? "
                + "그 기능은 없어요. roomId=2, 2026-11-06, no-show는 그대로.");
        assertThat(ReplyPolish.hideToolNames("비밀번호는 https://mspace.example/reset_password 에서, 문의는 help_desk@school.ac.kr로"))
                .as("URL·메일 주소 안은 그대로").isEqualTo("비밀번호는 https://mspace.example/reset_password 에서, 문의는 help_desk@school.ac.kr로");
        assertThat(ReplyPolish.hideToolNames("cancel_reservation과 usage_rules가 있어요"))
                .isEqualTo("예약 취소와 이용 규칙이 있어요");
        assertThat(ReplyPolish.hideToolNames("밑줄 없는 답")).isEqualTo("밑줄 없는 답");
        assertThat(ReplyPolish.TOOL_WORDS).as("mcp-server 도구 12개 + briefing").hasSize(13);
    }

    @Test
    @DisplayName("밑줄 꼴이 어긋나거나 낱말에 붙은 snake_case는 그대로 두고, 따옴표는 같이 지운다")
    void toolNameEdges() {
        // given
        String quoted = "'week_plan'과 \"my_schedule\"을 보세요";
        String malformed = "a__b, _room, room_, Room_status, room_status2, x/room_status, 가room_status";
        String addresses = "www.mspace.example/my_page 와 a.b_c@x 는 그대로, 끝은 room_status";
        String notFooter = "본문 (예약 시스템 15:00 다른 말)";

        // when
        String quotedOut = ReplyPolish.hideToolNames(quoted);
        String malformedOut = ReplyPolish.hideToolNames(malformed);
        String addressesOut = ReplyPolish.hideToolNames(addresses);
        String notFooterOut = ReplyPolish.stripFooters(notFooter);

        // then
        assertThat(quotedOut).isEqualTo("주간 계획과 내 시간표를 보세요");
        assertThat(malformedOut).as("이중 밑줄·앞뒤 밑줄·대문자·숫자·경로·한글에 붙은 것은 그대로").isEqualTo(malformed);
        assertThat(addressesOut).isEqualTo("www.mspace.example/my_page 와 a.b_c@x 는 그대로, 끝은 지금 빈 방");
        assertThat(notFooterOut).as("괄호 안이 꼬리말이 아니면 그대로").isEqualTo(notFooter);
    }

    @Test
    @DisplayName("공백·글자가 아주 길게 이어진 답도 금방 처리한다 (정규식 되짚기로 느려지지 않음)")
    void longInputStaysFast() {
        // given
        String spaces = "a" + " ".repeat(200_000) + "b";
        String letters = "a".repeat(200_000) + "_" + "b".repeat(200_000) + "@" + "c".repeat(100_000);
        String manyFooters = "본문" + " (입력한 시간표 기준)".repeat(20_000);
        String manyTokensAndMails = "room_status a@b.co https://x.kr/a_b ".repeat(20_000);
        String expectedMany = "지금 빈 방 a@b.co https://x.kr/a_b ".repeat(20_000);

        // when, then: 2초 안에 끝나고 결과는 그대로
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(ReplyPolish.stripFooters(spaces)).isEqualTo(spaces);
            assertThat(ReplyPolish.hideToolNames(letters)).isEqualTo(letters);
            assertThat(ReplyPolish.stripFooters(manyFooters)).isEqualTo("본문");
            assertThat(ReplyPolish.hideToolNames(manyTokensAndMails)).isEqualTo(expectedMany);
        });
    }
}
