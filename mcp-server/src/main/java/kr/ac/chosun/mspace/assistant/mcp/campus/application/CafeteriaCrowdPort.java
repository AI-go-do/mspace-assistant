package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.util.Optional;

/**
 * 학교 식당 혼잡도. 11차 회의(11/13): 데이터·센서가 없어 **인터페이스만** 둔다.
 * 나중에 식당 입구 카메라 인원 세기나 결제 건수 API가 생기면 이 포트 뒤에 어댑터를 붙인다.
 */
public interface CafeteriaCrowdPort {

    /** 연결된 데이터가 없으면 empty */
    Optional<CafeteriaCrowd> now();
}
