package kr.ac.chosun.mspace.assistant.mcp.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.BusArrivals;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CampusProperties;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CityBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 시내버스 도착: 키가 없을 때·실패할 때 지어내지 않는지 */
class CityBusTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T05:30:00Z"), ZoneId.of("UTC"));

    static CampusProperties props(String key) {
        return new CampusProperties(null, null,
                new CampusProperties.CityBus(key, null, null, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("키가 없으면 API를 부르지 않고 \"아직 연결 전\"과 정류장 번호 1177을 알려 준다")
    void notConnected() {
        // given
        AtomicInteger calls = new AtomicInteger();

        // when
        BusArrivals a = new CityBus(props(""), () -> {
            calls.incrementAndGet();
            return Optional.of(List.of());
        }, CLOCK).now();

        // then
        assertThat(calls).hasValue(0);
        assertThat(a.code()).isEqualTo("NOT_CONNECTED");
        assertThat(a.note()).contains("아직 연결 전", "1177", "IT융합대학");
        assertThat(a.buses()).isEmpty();
    }

    @Test
    @DisplayName("API가 실패하면 BUS_UNAVAILABLE, 버스를 지어내지 않는다")
    void unavailable() {
        // when
        BusArrivals a = new CityBus(props("k"), Optional::empty, CLOCK).now();

        // then
        assertThat(a.code()).isEqualTo("BUS_UNAVAILABLE");
        assertThat(a.buses()).isEmpty();
        assertThat(a.note()).contains("1177");
    }

    @Test
    @DisplayName("오는 버스가 없으면 정상(code 없음)이고 \"오고 있는 버스가 없어요\"")
    void none() {
        // when
        BusArrivals a = new CityBus(props("k"), () -> Optional.of(List.of()), CLOCK).now();

        // then
        assertThat(a.code()).isNull();
        assertThat(a.note()).contains("오고 있는 버스가 없어요");
        assertThat(a.measuredAt().toString()).isEqualTo("2026-11-13T14:30+09:00");
    }

    @Test
    @DisplayName("키가 있으면 http 주소를 막는다 (키가 평문으로 나가지 않게). 로컬 가짜 서버만 예외")
    void httpsOnlyWithKey() {
        // when / then
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new CampusProperties.CityBus("k", "http://apis.data.go.kr/6290000/gj_bis", null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        for (String bad : List.of("http://localhost.example.com", "http://127.0.0.1.evil.net", "http://localhost@evil.net", "ftp://localhost")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            new CampusProperties.CityBus("k", bad, null, null, null, null, null, null, null))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new CampusProperties.CityBus("k", "http://localhost:8089", null, null, null, null, null, null, null).connected())
                .isTrue();
        assertThat(new CampusProperties.CityBus("", "http://apis.data.go.kr", null, null, null, null, null, null, null).connected())
                .isFalse();
    }
}
