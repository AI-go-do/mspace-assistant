package kr.ac.chosun.mspace.assistant.mcp.campus.adapter.out.openmeteo;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CampusProperties;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.Forecast;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.WeatherPort;
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
 * Open-Meteo 예보 (https://open-meteo.com, 키 없음·비상업 무료). 학교 좌표만 보내고 학생 정보는 보내지 않는다.
 * 같은 예보를 여러 학생이 묻으니 설정한 시간(기본 10분) 동안 기억해 둔다 (예보 서버 호출을 줄이고 브리핑을 빠르게).
 */
@Component
class OpenMeteoWeatherClient implements WeatherPort {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherClient.class);

    private final RestClient rest;
    private final JsonMapper json;
    private final CampusProperties.Weather props;
    private final Clock clock;
    private volatile Cached cached;

    OpenMeteoWeatherClient(CampusProperties props, JsonMapper json, Clock clock) {
        this.props = props.weather();
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(this.props.timeout()).build());
        factory.setReadTimeout(this.props.timeout());
        this.rest = RestClient.builder().baseUrl(this.props.baseUrl()).requestFactory(factory).build();
        this.json = json;
        this.clock = clock;
    }

    private record Cached(Instant at, Forecast forecast) {
    }

    @Override
    public Optional<Forecast> forecast() {
        Cached c = cached;
        Instant now = clock.instant();
        // 서울 날짜가 바뀌면 남은 시간과 상관없이 새로 받는다 (23:59에 받은 예보에는 모레가 없다)
        if (c != null && c.at().plus(props.cache()).isAfter(now) && seoulDate(c.at()).equals(seoulDate(now))) {
            return Optional.of(c.forecast());
        }
        long started = System.nanoTime();
        boolean ok = false;
        try {
            String body = rest.get().uri(b -> b.path("/v1/forecast")
                            .queryParam("latitude", props.latitude())
                            .queryParam("longitude", props.longitude())
                            .queryParam("current", "temperature_2m,weather_code")
                            .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                            .queryParam("timezone", "Asia/Seoul")
                            .queryParam("forecast_days", 2)
                            .build())
                    .retrieve().body(String.class);
            Optional<Forecast> parsed = parse(body);
            parsed.ifPresent(f -> cached = new Cached(now, f));
            ok = parsed.isPresent();
            return parsed;
        } catch (RestClientException e) {
            log.warn("weather 호출 실패 cause={}", e.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            log.info("weather ok={} {}ms", ok, (System.nanoTime() - started) / 1_000_000);
        }
    }

    Optional<Forecast> parse(String body) {
        if (body == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = json.readTree(body);
            JsonNode daily = root.path("daily");
            JsonNode dates = daily.path("time");
            if (!dates.isArray()) {
                return Optional.empty();
            }
            List<Forecast.Day> days = new ArrayList<>();
            for (int i = 0; i < dates.size(); i++) {
                days.add(new Forecast.Day(LocalDate.parse(dates.get(i).asString()),
                        intAt(daily.path("weather_code"), i), doubleAt(daily.path("temperature_2m_min"), i),
                        doubleAt(daily.path("temperature_2m_max"), i), intAt(daily.path("precipitation_probability_max"), i)));
            }
            if (days.isEmpty()) {
                return Optional.empty();
            }
            JsonNode current = root.path("current");
            Double nowTemp = current.path("temperature_2m").isNumber() ? current.path("temperature_2m").asDouble() : null;
            Integer nowCode = current.path("weather_code").isNumber() ? current.path("weather_code").asInt() : null;
            return Optional.of(new Forecast(nowTemp, nowCode, List.copyOf(days)));
        } catch (JacksonException | DateTimeParseException e) {
            log.warn("weather 응답 해석 실패 cause={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static LocalDate seoulDate(Instant t) {
        return t.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate();
    }

    private static Integer intAt(JsonNode array, int i) {
        JsonNode v = array.path(i);
        return v.isNumber() ? v.asInt() : null;
    }

    private static Double doubleAt(JsonNode array, int i) {
        JsonNode v = array.path(i);
        return v.isNumber() ? v.asDouble() : null;
    }
}
