package kr.ac.chosun.mspace.assistant.agent.chat.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyPolishTest {

    @Test
    @DisplayName("모델이 직접 쓴 꼬리말은 끝에서 모두 지운다 (두 번 붙어도, 시간표 꼬리말도)")
    void stripsModelWrittenFooters() {
        // given
        String twice = "2번 방이 비어 있어요\n\n(예약 시스템 15:00 조회 기준)\n\n(예약 시스템 15:00 조회 기준)";
        String both = "수업 끝나고 쓸 수 있어요 (예약 시스템 9:05 조회 · 입력한 시간표 기준)";
        String schedule = "오늘 수업은 둘이에요\n(입력한 시간표 기준)\n";

        // when / then
        assertThat(ReplyPolish.stripFooters(twice)).isEqualTo("2번 방이 비어 있어요");
        assertThat(ReplyPolish.stripFooters(both)).isEqualTo("수업 끝나고 쓸 수 있어요");
        assertThat(ReplyPolish.stripFooters(schedule)).isEqualTo("오늘 수업은 둘이에요");
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
        assertThat(out).isEqualTo("지금 바로 쓰기으로 지금 바로 잡을 수 있어요. 지금 빈 방를 다시 볼까요? "
                + "그 기능는 없어요. roomId=2, 2026-11-06, no-show는 그대로.");
        assertThat(ReplyPolish.hideToolNames("밑줄 없는 답")).isEqualTo("밑줄 없는 답");
        assertThat(ReplyPolish.TOOL_WORDS).as("mcp-server 도구 12개 + briefing").hasSize(13);
    }
}
