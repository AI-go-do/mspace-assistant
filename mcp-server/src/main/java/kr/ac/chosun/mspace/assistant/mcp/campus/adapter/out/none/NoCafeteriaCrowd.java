package kr.ac.chosun.mspace.assistant.mcp.campus.adapter.out.none;

import java.util.Optional;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CafeteriaCrowd;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CafeteriaCrowdPort;
import org.springframework.stereotype.Component;

/** 아직 데이터 없음. 다른 어댑터가 생기면 이 클래스를 바꾼다 (11차 회의: 인터페이스만) */
@Component
class NoCafeteriaCrowd implements CafeteriaCrowdPort {

    @Override
    public Optional<CafeteriaCrowd> now() {
        return Optional.empty();
    }
}
