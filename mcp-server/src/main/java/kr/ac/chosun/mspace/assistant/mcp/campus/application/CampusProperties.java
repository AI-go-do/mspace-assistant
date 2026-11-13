package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 학교 생활 도구 설정 (application.yml의 mspace.campus). 11차 회의(11/13) 재웅 제안: 아침 브리핑에 날씨·교내 셔틀.
 *
 * @param weather 날씨 예보 (Open-Meteo, 키 없음)
 * @param shuttle 교내 셔틀 시간표 (정해진 시각. 실시간 위치 아님)
 */
@ConfigurationProperties("mspace.campus")
public record CampusProperties(Weather weather, Shuttle shuttle) {

    public CampusProperties {
        weather = weather == null ? new Weather(true, null, 0, 0, null, null, 0, null) : weather;
        shuttle = shuttle == null ? new Shuttle(null, List.of()) : shuttle;
    }

    /**
     * @param enabled         false면 도구가 "지금은 날씨를 볼 수 없어요"로 답한다
     * @param baseUrl         예보 서버 주소. 테스트에서 가짜 서버로 바꾼다
     * @param latitude        위도 (조선대, 확인 필요)
     * @param longitude       경도
     * @param place           답에 쓰는 지역 이름
     * @param timeout         예보 서버 연결·읽기 제한
     * @param umbrellaPercent 비 올 확률이 이 값 이상이면 우산 챙기라고 한다
     * @param cache           같은 예보를 다시 쓰는 시간 (예보 서버 호출을 줄인다). 테스트는 0
     */
    public record Weather(boolean enabled, String baseUrl, double latitude, double longitude, String place,
                          Duration timeout, int umbrellaPercent, Duration cache) {

        public Weather {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.open-meteo.com" : baseUrl.strip();
            latitude = latitude == 0 ? 35.1408 : latitude;
            longitude = longitude == 0 ? 126.9300 : longitude;
            place = place == null || place.isBlank() ? "광주 조선대" : place;
            timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
            umbrellaPercent = umbrellaPercent <= 0 ? 50 : umbrellaPercent;
            cache = cache == null ? Duration.ofMinutes(10) : cache;
        }
    }

    /**
     * @param basis  답에 꼭 붙이는 한 줄 (시간표 기준이라 늦거나 빠를 수 있다)
     * @param routes 노선별 출발 시각. 비어 있으면 도구가 "시간표가 아직 없어요"로 답한다 (지어내지 않게)
     */
    public record Shuttle(String basis, List<Route> routes) {

        public Shuttle {
            basis = basis == null || basis.isBlank()
                    ? "학교가 정한 시간표 기준이에요. 실시간 위치가 아니라 조금 늦거나 빠를 수 있어요." : basis;
            routes = routes == null ? List.of() : List.copyOf(routes);
        }
    }

    /**
     * @param name       노선 이름 (예: "교내 순환")
     * @param stop       타는 곳 (예: "IT융합대학 앞")
     * @param days       운행 요일. 비면 월~금
     * @param departures 그 정류장 출발 시각 (HH:mm)
     */
    public record Route(String name, String stop, List<DayOfWeek> days, List<LocalTime> departures) {

        public Route {
            days = days == null || days.isEmpty()
                    ? List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                    : List.copyOf(days);
            departures = departures == null ? List.of() : departures.stream().sorted().toList();
        }
    }
}
