package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 학교 생활 도구 설정 (application.yml의 mspace.campus). 11차 회의(11/13) 재웅 제안: 아침 브리핑에 날씨·교내 셔틀.
 *
 * @param weather 날씨 예보 (Open-Meteo, 키 없음)
 * @param shuttle 교내 셔틀 시간표 (정해진 시각. 실시간 위치 아님)
 * @param cityBus IT융합대학 앞 시내버스 도착 정보 (광주 버스정보 공공 API, 키 필요)
 */
@ConfigurationProperties("mspace.campus")
public record CampusProperties(Weather weather, Shuttle shuttle, CityBus cityBus) {

    public CampusProperties {
        weather = weather == null ? new Weather(true, null, 0, 0, null, null, 0, null) : weather;
        shuttle = shuttle == null ? new Shuttle(null, null, List.of()) : shuttle;
        cityBus = cityBus == null ? new CityBus(null, null, null, null, null, null, null, null, null) : cityBus;
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
     * 시내버스 도착 정보. 공공데이터포털 "전남광주통합특별시_광주버스정보"(https://www.data.go.kr/data/15157923/openapi.do).
     * 서비스 키가 비면 도구가 "아직 연결 전"으로 답한다 (키는 .env의 GWANGJU_BUS_KEY에만, 커밋하지 않는다).
     *
     * @param serviceKey  공공데이터포털에서 받은 일반 인증키(Decoding 값)
     * @param baseUrl     API 주소. 테스트에서 가짜 서버로 바꾼다
     * @param arrivePath  도착 정보 경로 (키 받은 뒤 실제 응답으로 확인 필요)
     * @param stationPath 정류소 정보 경로 (ARS 번호 → 정류소 ID 찾기)
     * @param arsId       정류장 표지판 번호. IT융합대학 앞은 1177 (영준 11/13)
     * @param busstopId   API가 쓰는 정류소 ID. 비면 처음 한 번 정류소 정보에서 arsId로 찾는다
     * @param stopName    답에 쓰는 정류장 이름
     * @param timeout     연결·읽기 제한
     * @param cache       같은 도착 정보를 다시 쓰는 시간 (학생이 많아도 API 호출을 줄인다)
     */
    public record CityBus(String serviceKey, String baseUrl, String arrivePath, String stationPath, String arsId,
                          String busstopId, String stopName, Duration timeout, Duration cache) {

        public CityBus {
            serviceKey = serviceKey == null ? "" : serviceKey.strip();
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://apis.data.go.kr/6290000/gj_bis" : baseUrl.strip();
            arrivePath = arrivePath == null || arrivePath.isBlank() ? "/arriveInfo" : arrivePath.strip();
            stationPath = stationPath == null || stationPath.isBlank() ? "/stationInfo" : stationPath.strip();
            arsId = arsId == null || arsId.isBlank() ? "1177" : arsId.strip();
            busstopId = busstopId == null ? "" : busstopId.strip();
            stopName = stopName == null || stopName.isBlank() ? "IT융합대학" : stopName;
            timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
            cache = cache == null ? Duration.ofSeconds(30) : cache;
            // 키는 주소 뒤에 붙어 가므로 평문 HTTP로 보내지 않는다 (테스트용 로컬 가짜 서버만 예외)
            if (!serviceKey.isBlank() && !baseUrl.startsWith("https://") && !isLocal(baseUrl)) {
                throw new IllegalArgumentException("시내버스 API 키가 있으면 주소는 https여야 해요");
            }
        }

        private static boolean isLocal(String url) {
            return url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1");
        }

        public boolean connected() {
            return !serviceKey.isBlank();
        }
    }

    /** 학기 중·방학 중. 학교 공지가 둘을 나눠 적는다 */
    public enum Season { SEMESTER, VACATION }

    /**
     * @param basis  답에 꼭 붙이는 한 줄 (시간표 기준이라 늦거나 빠를 수 있다)
     * @param season 지금 학기 중인지 방학 중인지. 노선의 season과 맞는 것만 쓴다. 비면 학기 중
     * @param routes 노선별 시간표. 비어 있으면 도구가 "시간표가 아직 없어요"로 답한다 (지어내지 않게)
     */
    public record Shuttle(String basis, Season season, List<Route> routes) {

        public Shuttle {
            basis = basis == null || basis.isBlank()
                    ? "학교가 정한 시간표 기준이에요. 실시간 위치가 아니라 조금 늦거나 빠를 수 있어요." : basis;
            season = season == null ? Season.SEMESTER : season;
            routes = routes == null ? List.of() : List.copyOf(routes);
        }
    }

    /**
     * @param name       노선 이름 (예: "본관 순환 버스")
     * @param stop       운행 구간 또는 타는 곳 (공지에 적힌 그대로)
     * @param season     이 시간표를 쓰는 때. 비면 학기 중·방학 중 모두
     * @param days       운행 요일. 비면 월~금
     * @param departures 정해진 출발 시각 (HH:mm)
     * @param windows    시간대 운행 (공지가 "07:40~17:00 수시", "17:00~18:45 15분 간격"처럼 적은 것)
     */
    public record Route(String name, String stop, Season season, List<DayOfWeek> days, List<LocalTime> departures,
                        List<Window> windows) {

        public Route {
            days = days == null || days.isEmpty()
                    ? List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                    : List.copyOf(days);
            windows = windows == null ? List.of() : windows.stream().sorted(java.util.Comparator.comparing(Window::from)).toList();
            List<LocalTime> all = new ArrayList<>(departures == null ? List.of() : departures);
            windows.forEach(w -> all.addAll(w.departures()));
            departures = all.stream().distinct().sorted().toList();
        }

        /** 이 시각에 걸친, 간격이 없는 시간대 ("수시 운행" 등). 없으면 null */
        public Window runningAt(LocalTime t) {
            return windows.stream().filter(w -> w.every() == null && !t.isBefore(w.from()) && t.isBefore(w.to()))
                    .findFirst().orElse(null);
        }

        /** t 뒤에 시작하는 첫 시각 없는 시간대 (예: 07:00에 본 "07:40~17:00 수시 운행"). 없으면 null */
        public Window upcomingAfter(LocalTime t) {
            return windows.stream().filter(w -> w.every() == null && w.from().isAfter(t)).findFirst().orElse(null);
        }

        public boolean runs(Season now) {
            return season == null || season == now;
        }
    }

    /**
     * @param from  시작 (HH:mm)
     * @param to    끝 (HH:mm)
     * @param every 배차 간격. 있으면 from부터 to까지 이 간격의 출발 시각으로 펼친다. 없으면 "수시 운행"처럼 시각 없이 안내만
     * @param note  학생에게 그대로 전할 말 (예: "수시 운행(5~10분 간격)", "본관 버스 이용")
     */
    public record Window(LocalTime from, LocalTime to, Duration every, String note) {

        public Window {
            if (from == null || to == null || to.isBefore(from)) {
                throw new IllegalArgumentException("셔틀 시간대는 from ≤ to여야 해요: " + from + "~" + to);
            }
            // 하루 이상이면 LocalTime 덧셈이 같은 시각으로 돌아와 펼치기가 끝나지 않는다
            if (every != null && (every.isZero() || every.isNegative() || every.compareTo(Duration.ofDays(1)) >= 0)) {
                throw new IllegalArgumentException("셔틀 배차 간격은 0보다 크고 하루보다 짧아야 해요: " + every);
            }
        }

        /** 간격 운행이면 from, from+every, … ≤ to. 아니면 빈 목록 */
        List<LocalTime> departures() {
            if (every == null) {
                return List.of();
            }
            List<LocalTime> out = new ArrayList<>();
            for (LocalTime t = from; !t.isAfter(to); t = t.plus(every)) {
                out.add(t);
                if (t.plus(every).isBefore(t)) { // 자정을 넘기면 멈춘다
                    break;
                }
            }
            return out;
        }

        /** "07:40~17:00 수시 운행(5~10분 간격)" */
        public String text() {
            String range = "%02d:%02d~%02d:%02d".formatted(from.getHour(), from.getMinute(), to.getHour(), to.getMinute());
            return note == null || note.isBlank() ? range : range + " " + note;
        }
    }
}
