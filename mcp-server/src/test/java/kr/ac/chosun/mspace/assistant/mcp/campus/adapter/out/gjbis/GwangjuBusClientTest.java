package kr.ac.chosun.mspace.assistant.mcp.campus.adapter.out.gjbis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.BusArrivals;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CampusProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 도착 응답 해석: 정상 0건과 해석 실패를 가른다 */
class GwangjuBusClientTest {

    final GwangjuBusClient client = new GwangjuBusClient(
            new CampusProperties(null, null, new CampusProperties.CityBus("", null, null, null, null, null, null, null, null)),
            JsonMapper.builder().build(), Clock.systemUTC());

    @Test
    @DisplayName("0건 응답은 \"오는 버스 없음\"(빈 목록)")
    void zeroRows() {
        // given / when / then: 해석만 보는 한 줄 검사
        assertThat(client.parseArrivals("{\"ROW_COUNT\":0}")).hasValueSatisfying(b -> assertThat(b).isEmpty());
    }

    @Test
    @DisplayName("N건이라면서 목록이 없거나, 모르는 모양이면 실패로 본다 (\"오는 버스 없음\"으로 바꾸지 않음)")
    void brokenShapes() {
        // given / when / then: 모양이 깨진 응답 세 가지
        assertThat(client.parseArrivals("{\"ROW_COUNT\":2}")).isEmpty();
        assertThat(client.parseArrivals("{\"error\":\"SERVICE_KEY_IS_NOT_REGISTERED\"}")).isEmpty();
        assertThat(client.parseArrivals("{\"ROW_COUNT\":1,\"BUSSTOP_LIST\":[{\"REMAIN_MIN\":3}]}")).isEmpty();
    }

    @Test
    @DisplayName("항목이 하나면 객체로 와도 읽고, 남은 분 순으로 정렬한다")
    void singleObjectAndSort() {
        // given / when / then: 목록 대신 객체 하나로 온 응답
        assertThat(client.parseArrivals("{\"ROW_COUNT\":1,\"BUSSTOP_LIST\":{\"LINE_NAME\":\"419\",\"REMAIN_MIN\":\"4\"}}"))
                .hasValueSatisfying(b -> assertThat(b).extracting("line", "minutes").containsExactly(org.assertj.core.groups.Tuple.tuple("419", 4)));
    }

    @Test
    @DisplayName("캐시가 빈 순간 동시에 8명이 물어도 도착 API는 한 번만 부른다 (성공)")
    void concurrentSuccessCallsOnce() throws Exception {
        // given: 도착 1건을 주는 가짜 API / when: 8명 동시 조회 / then: 호출 1번
        assertThat(concurrentCalls(200, "{\"ROW_COUNT\":1,\"BUSSTOP_LIST\":[{\"LINE_NAME\":\"419\",\"REMAIN_MIN\":4}]}"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("API가 실패해도 캐시 시간 동안 실패를 기억해 동시에 8명이 물어도 한 번만 부른다 (실패)")
    void concurrentFailureCallsOnce() throws Exception {
        // given: 500을 주는 가짜 API / when: 8명 동시 조회 / then: 호출 1번
        assertThat(concurrentCalls(500, "{}")).isEqualTo(1);
    }

    /** 가짜 도착 API를 띄우고 8명이 동시에 물은 뒤 서버가 받은 호출 수를 돌려준다 */
    private int concurrentCalls(int status, String body) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/arriveInfo", ex -> {
            hits.incrementAndGet();
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var c = new GwangjuBusClient(
                    new CampusProperties(null, null, new CampusProperties.CityBus("test-key",
                            "http://127.0.0.1:" + server.getAddress().getPort(), null, null, null, "2873", null,
                            Duration.ofSeconds(3), Duration.ofSeconds(30))),
                    JsonMapper.builder().build(), Clock.systemUTC());
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Optional<List<BusArrivals.Bus>>>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return c.arrivals();
                }, pool));
            }
            start.countDown();
            for (var f : calls) {
                assertThat(f.get()).matches(r -> status == 200 ? r.isPresent() : r.isEmpty());
            }
            return hits.get();
        } finally {
            server.stop(0);
        }
    }
}
