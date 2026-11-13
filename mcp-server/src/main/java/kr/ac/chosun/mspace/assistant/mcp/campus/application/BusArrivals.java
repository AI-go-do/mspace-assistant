package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * city_bus_arrival 도구 결과.
 *
 * @param stop       정류장 이름
 * @param arsId      정류장 표지판 번호 (광주 버스 앱에서 이 번호로 찾을 수 있다)
 * @param code       키가 없으면 NOT_CONNECTED, API 실패면 BUS_UNAVAILABLE, 정상이면 null
 * @param buses      곧 오는 버스 (가까운 순). 정상인데 비면 지금 오는 버스가 없다
 * @param measuredAt 조회한 시각
 * @param note       학생에게 그대로 전할 문장. 없으면 null
 */
public record BusArrivals(String stop, String arsId, String code, List<Bus> buses, OffsetDateTime measuredAt, String note) {

    /**
     * @param line    노선 번호 (예: "419")
     * @param minutes 도착까지 남은 분 (API 값 그대로)
     * @param stops   남은 정류장 수. 모르면 null
     */
    public record Bus(String line, Integer minutes, Integer stops) {
    }
}
