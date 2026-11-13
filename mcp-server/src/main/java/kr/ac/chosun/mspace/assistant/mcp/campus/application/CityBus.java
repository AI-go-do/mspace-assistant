package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import org.springframework.stereotype.Service;

/**
 * IT융합대학 앞 시내버스 도착 (영준 11/13: 정류장 1177). 키가 없으면 지어내지 않고 "아직 연결 전",
 * API가 실패하면 BUS_UNAVAILABLE로 답한다. 둘 다 정류장 번호를 같이 알려 학생이 앱에서 찾게 한다.
 */
@Service
public class CityBus {

    static final String NOT_CONNECTED_NOTE = "시내버스 도착 정보는 아직 연결 전이에요. 광주 버스 앱에서 정류장 번호 %s(%s)를 찾아 주세요.";
    static final String UNAVAILABLE_NOTE = "지금 시내버스 도착 정보를 받지 못했어요. 광주 버스 앱에서 정류장 번호 %s(%s)를 찾아 주세요.";
    static final String NONE_NOTE = "지금 이 정류장으로 오고 있는 버스가 없어요.";
    static final String BASIS = "광주 버스정보 기준이에요. 도로 사정에 따라 몇 분 달라질 수 있어요.";

    private final CampusProperties.CityBus props;
    private final BusArrivalPort port;
    private final Clock clock;

    public CityBus(CampusProperties props, BusArrivalPort port, Clock clock) {
        this.props = props.cityBus();
        this.port = port;
        this.clock = clock;
    }

    public BusArrivals now() {
        OffsetDateTime at = OffsetDateTime.now(clock.withZone(SeoulTime.ZONE)).withNano(0);
        if (!props.connected()) {
            return new BusArrivals(props.stopName(), props.arsId(), "NOT_CONNECTED", List.of(), at,
                    NOT_CONNECTED_NOTE.formatted(props.arsId(), props.stopName()));
        }
        Optional<List<BusArrivals.Bus>> buses = port.arrivals();
        if (buses.isEmpty()) {
            return new BusArrivals(props.stopName(), props.arsId(), "BUS_UNAVAILABLE", List.of(), at,
                    UNAVAILABLE_NOTE.formatted(props.arsId(), props.stopName()));
        }
        return new BusArrivals(props.stopName(), props.arsId(), null, buses.get(), at,
                buses.get().isEmpty() ? NONE_NOTE : BASIS);
    }
}
