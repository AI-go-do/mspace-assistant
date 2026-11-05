package kr.ac.chosun.mspace.assistant.mcp.rule.application;

import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;

/** 이용 규칙 조회 포트. 구현은 adapter/out/core */
public interface UsageRulesPort {

    UsageRules find(UserCredential credential);
}
