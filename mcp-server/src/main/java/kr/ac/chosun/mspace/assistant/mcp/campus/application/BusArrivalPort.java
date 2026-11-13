package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.util.List;
import java.util.Optional;

/** 시내버스 도착 정보 (광주 버스정보 API). 키가 없거나 실패하면 빈 값 */
public interface BusArrivalPort {

    /** 남은 시간 순으로. 실패·해석 불가면 Optional.empty() */
    Optional<List<BusArrivals.Bus>> arrivals();
}
