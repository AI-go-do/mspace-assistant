package kr.ac.chosun.mspace.assistant.mcp.rule.adapter.out.core;

import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreHttp;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.rule.application.UsageRules;
import kr.ac.chosun.mspace.assistant.mcp.rule.application.UsageRulesPort;
import org.springframework.stereotype.Component;

/** 코어 GET /api/rules (core RulesController, FR-WEB-003). 로그인 없이도 열리지만 다른 도구와 같게 학생 헤더를 넘긴다 */
@Component
class CoreRulesClient implements UsageRulesPort {

    private final CoreHttp core;

    CoreRulesClient(CoreHttp core) {
        this.core = core;
    }

    @Override
    public UsageRules find(UserCredential credential) {
        UsageRules rules = core.call("GET /api/rules", credential, rest -> rest.get().uri("/api/rules"), UsageRules.class);
        if (rules == null || !rules.complete()) {
            // 규칙을 모르면 지어내지 않게 오류로 돌려준다 (숫자 하나라도 빠지면 0으로 안내하지 않게)
            throw new CoreApiException(CoreHttp.BAD_RESPONSE, "이용 규칙을 불러오지 못했어요. 앱의 이용 규칙 화면을 확인해 주세요.", 502);
        }
        return rules;
    }
}
