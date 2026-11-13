package kr.ac.chosun.mspace.assistant.mcp.campus.adapter.out.gjbis;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.BusArrivalPort;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.BusArrivals;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CampusProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 광주 버스정보 공공 API (공공데이터포털 15157923, 무료). 학교 정류장 번호만 보내고 학생 정보는 보내지 않는다.
 * 필드 이름(BUSSTOP_LIST·LINE_NAME·REMAIN_MIN·REMAIN_STOP, STATION_LIST·ARS_ID·BUSSTOP_ID)은 광주 BIS의 기존 응답 모양을 따랐고,
 * 키를 받은 뒤 실제 응답으로 확인해야 한다 (확인 필요). 모양이 다르면 지어내지 않고 실패로 돌려준다.
 */
@Component
class GwangjuBusClient implements BusArrivalPort {

    private static final Logger log = LoggerFactory.getLogger(GwangjuBusClient.class);

    private final RestClient rest;
    private final JsonMapper json;
    private final CampusProperties.CityBus props;
    private final Clock clock;
    private volatile String busstopId;
    private volatile Cached cached;

    GwangjuBusClient(CampusProperties props, JsonMapper json, Clock clock) {
        this.props = props.cityBus();
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(this.props.timeout()).build());
        factory.setReadTimeout(this.props.timeout());
        this.rest = RestClient.builder().baseUrl(this.props.baseUrl()).requestFactory(factory).build();
        this.json = json;
        this.clock = clock;
        this.busstopId = this.props.busstopId().isBlank() ? null : this.props.busstopId();
    }

    private record Cached(Instant at, List<BusArrivals.Bus> buses) {
    }

    @Override
    public Optional<List<BusArrivals.Bus>> arrivals() {
        if (!props.connected()) {
            return Optional.empty();
        }
        Cached c = cached;
        if (fresh(c, clock.instant())) {
            return Optional.of(c.buses());
        }
        // 캐시가 끝난 순간 학생 여럿이 물어도 API는 한 번만 부른다: 한 요청만 갱신하고 나머지는 기다렸다 새 값을 쓴다
        synchronized (this) {
            c = cached;
            Instant now = clock.instant();
            if (fresh(c, now)) {
                return Optional.of(c.buses());
            }
            return refresh(now);
        }
    }

    private boolean fresh(Cached c, Instant now) {
        return c != null && c.at().plus(props.cache()).isAfter(now);
    }

    private Optional<List<BusArrivals.Bus>> refresh(Instant now) {
        long started = System.nanoTime();
        boolean ok = false;
        try {
            String id = busstopId();
            if (id == null) {
                return Optional.empty();
            }
            Optional<List<BusArrivals.Bus>> parsed = parseArrivals(get(props.arrivePath(), "BUSSTOP_ID", id));
            parsed.ifPresent(b -> cached = new Cached(now, b));
            ok = parsed.isPresent();
            return parsed;
        } catch (RestClientException e) {
            log.warn("citybus 호출 실패 cause={}", e.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            log.info("citybus ok={} {}ms", ok, (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** 표지판 번호(ARS)와 API의 정류소 ID는 다른 체계라, ID를 안 정했으면 정류소 정보에서 한 번 찾아 기억한다 */
    private String busstopId() {
        String id = busstopId;
        if (id != null) {
            return id;
        }
        id = findBusstopId(get(props.stationPath(), null, null), props.arsId());
        if (id == null) {
            log.warn("citybus 정류소 ID를 찾지 못함");
        }
        busstopId = id;
        return id;
    }

    private String get(String path, String param, String value) {
        return rest.get().uri(b -> {
            b.path(path).queryParam("serviceKey", props.serviceKey()).queryParam("resultType", "json");
            if (param != null) {
                b.queryParam(param, value);
            }
            return b.build();
        }).retrieve().body(String.class);
    }

    String findBusstopId(String body, String arsId) {
        try {
            for (JsonNode s : list(body, "STATION_LIST")) {
                if (arsId.equals(text(s, "ARS_ID"))) {
                    return text(s, "BUSSTOP_ID");
                }
            }
        } catch (JacksonException e) {
            log.warn("citybus 정류소 응답 해석 실패 cause={}", e.getClass().getSimpleName());
        }
        return null;
    }

    Optional<List<BusArrivals.Bus>> parseArrivals(String body) {
        try {
            JsonNode root = body == null ? null : json.readTree(body);
            if (root == null) {
                return Optional.empty();
            }
            JsonNode rows = root.findValue("ROW_COUNT") != null ? root.findValue("ROW_COUNT") : root.findValue("ROWCOUNT");
            int rowCount = rows == null ? -1 : rows.asInt(-1);
            List<JsonNode> items = list(body, "BUSSTOP_LIST");
            // 모양을 모르는 응답(오류 XML 등)이나 "N건"이라면서 목록이 없는 응답은 "오는 버스 없음"으로 바꾸지 않는다
            if (items.isEmpty() && rowCount != 0) {
                return Optional.empty();
            }
            List<BusArrivals.Bus> buses = new ArrayList<>();
            for (JsonNode b : items) {
                String line = text(b, "LINE_NAME");
                if (line == null) {
                    return Optional.empty(); // 항목을 읽지 못하면 일부만 보여 주지 않는다
                }
                buses.add(new BusArrivals.Bus(line, number(b, "REMAIN_MIN"), number(b, "REMAIN_STOP")));
            }
            buses.sort(Comparator.comparing(BusArrivals.Bus::minutes, Comparator.nullsLast(Comparator.naturalOrder())));
            return Optional.of(List.copyOf(buses));
        } catch (JacksonException e) {
            log.warn("citybus 도착 응답 해석 실패 cause={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 이름이 같은 배열을 어디에 있든 찾는다 (응답을 한 겹 더 감싸는 경우가 있어서). 하나뿐이면 객체도 받는다 */
    private List<JsonNode> list(String body, String name) {
        if (body == null) {
            return List.of();
        }
        JsonNode node = json.readTree(body).findValue(name);
        if (node == null) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(out::add);
        } else if (node.isObject()) {
            out.add(node);
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isString() || v.isNumber() ? v.asString() : null;
    }

    private static Integer number(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isNumber()) {
            return v.asInt();
        }
        if (v.isString()) {
            try {
                return Integer.valueOf(v.asString().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
