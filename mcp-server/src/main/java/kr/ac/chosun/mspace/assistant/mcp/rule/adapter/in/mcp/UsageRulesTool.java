package kr.ac.chosun.mspace.assistant.mcp.rule.adapter.in.mcp;

import kr.ac.chosun.mspace.assistant.mcp.common.Credentials;
import kr.ac.chosun.mspace.assistant.mcp.rule.application.UsageRules;
import kr.ac.chosun.mspace.assistant.mcp.rule.application.UsageRulesPort;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/** 읽기 도구 usage_rules (FR-WEB-003). 설명 문장은 모델이 읽는다 */
@Component
class UsageRulesTool {

    private final UsageRulesPort rules;

    UsageRulesTool(UsageRulesPort rules) {
        this.rules = rules;
    }

    @McpTool(name = "usage_rules",
            description = """
                    M-SPACE 이용 규칙을 돌려준다. "음식 먹어도 돼?", "노쇼하면 어떻게 돼?", "며칠 전부터 예약돼?",
                    "연장 몇 번 돼?"처럼 규칙·벌칙을 물을 때 쓴다. 입력은 없다.
                    reservation은 예약 숫자(미리 예약 일수, 예약 단위 분, 1회 최대 분, 입실 기한 분, 연장 규칙),
                    conduct는 이용 수칙(title·rule·penalty), reminders는 체크인·퇴실 때 한 줄 안내다.
                    규칙은 이 결과에 있는 것만 말하고, 없는 규칙은 지어내지 않고 모른다고 한다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    UsageRules usageRules(McpSyncRequestContext context) {
        return rules.find(Credentials.require(context));
    }
}
