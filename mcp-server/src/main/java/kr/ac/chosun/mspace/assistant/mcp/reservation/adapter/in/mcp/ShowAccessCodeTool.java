package kr.ac.chosun.mspace.assistant.mcp.reservation.adapter.in.mcp;

import kr.ac.chosun.mspace.assistant.mcp.common.Credentials;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ShowAccessCode;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ShowAccessCode.Answer;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/**
 * 읽기 도구 show_access_code. 결과에 입실 번호가 없다: 모델 입력이 되기 때문이다.
 * cardShown이 true면 에이전트가 학생 화면에 카드를 띄우고, 카드가 REST로 번호를 따로 받는다 (설계 3장).
 */
@Component
class ShowAccessCodeTool {

    private final ShowAccessCode show;

    ShowAccessCodeTool(ShowAccessCode show) {
        this.show = show;
    }

    @McpTool(name = "show_access_code",
            description = """
                    학생이 입실 번호(문 여는 6자리 번호)를 물을 때 쓴다. 예: "입실 번호 알려 줘", "문 비밀번호 뭐였지?"
                    이 도구는 번호를 돌려주지 않는다. 번호는 학생 화면 카드에만 나온다. 번호를 추측하거나 지어내지 않는다.
                    결과의 message를 그대로 안내한다. cardShown이 true면 "화면 카드에서 확인해 주세요"라고 말한다.
                    대표자가 아니면 번호를 볼 수 없다는 message가 온다.
                    reservationId는 생략할 수 있다. 생략했을 때 끝나지 않은 예약이 1건이면 그 예약으로 답하고,
                    여러 건이면 choices(방·시간 목록)가 온다. 그때는 목록을 보여 주고 어느 예약인지 학생에게 물은 뒤
                    고른 reservationId를 넣어 다시 부른다. 마음대로 하나를 고르지 않는다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    Answer showAccessCode(McpSyncRequestContext context,
                          @McpToolParam(description = "예약 id (my_reservations나 choices의 id). 모르면 비운다", required = false)
                          Long reservationId) {
        return show.check(Credentials.require(context), reservationId);
    }
}
