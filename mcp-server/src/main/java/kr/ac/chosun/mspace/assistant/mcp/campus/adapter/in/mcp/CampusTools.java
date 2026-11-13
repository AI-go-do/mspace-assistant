package kr.ac.chosun.mspace.assistant.mcp.campus.adapter.in.mcp;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.BusArrivals;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CafeteriaCrowd;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CafeteriaCrowdPort;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CampusShuttle;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CampusWeather;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.CityBus;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.ShuttleTimes;
import kr.ac.chosun.mspace.assistant.mcp.campus.application.WeatherReport;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.Credentials;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/**
 * 학교 생활 읽기 도구 4개 (11차 회의 11/13, 재웅 제안: 아침 브리핑. 시내버스는 영준 11/13 추가). 예약 코어는 부르지 않는다.
 * 다른 도구와 같게 로그인한 학생만 쓰게 자격 정보는 확인한다(값은 쓰지 않음). 설명 문장은 모델이 읽는다.
 */
@Component
class CampusTools {

    static final String CAFETERIA_NOTE = "학교 식당 혼잡도는 아직 볼 수 없어요.";

    private final CampusWeather weather;
    private final CampusShuttle shuttle;
    private final CafeteriaCrowdPort cafeteria;
    private final CityBus cityBus;

    CampusTools(CampusWeather weather, CampusShuttle shuttle, CafeteriaCrowdPort cafeteria, CityBus cityBus) {
        this.weather = weather;
        this.shuttle = shuttle;
        this.cafeteria = cafeteria;
        this.cityBus = cityBus;
    }

    @McpTool(name = "campus_weather",
            description = """
                    학교(광주 조선대) 날씨 예보를 돌려준다. "오늘 비 와?", "우산 챙겨야 해?", "내일 날씨"처럼 날씨를 물을 때 쓴다.
                    오늘과 내일만 된다. summary는 그날 날씨, minTemperature·maxTemperature는 최저·최고 기온(℃),
                    rainChance는 비 올 확률(%), umbrella가 true면 우산을 챙기라고 말한다. nowTemperature는 오늘만 있는 지금 기온이다.
                    숫자는 결과에 있는 것만 말하고 note(출처)를 한 번 덧붙인다. 오류면 날씨를 지어내지 않는다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    WeatherReport campusWeather(
            McpSyncRequestContext context,
            @McpToolParam(description = "볼 날짜, YYYY-MM-DD. 오늘이나 내일. 없으면 오늘", required = false) String date) {
        Credentials.require(context);
        return weather.of(parseDate(date));
    }

    @McpTool(name = "campus_shuttle",
            description = """
                    교내 셔틀버스의 다음 출발 시각을 학교 시간표 기준으로 돌려준다. "셔틀 언제 와?", "다음 버스 몇 시야?",
                    "수업 끝나고 탈 셔틀"처럼 셔틀을 물을 때 쓴다. 실시간 위치는 모른다.
                    routes마다 name(노선), stop(운행 구간), next(가까운 출발 최대 3개, HH:mm), last(그날 마지막),
                    running(지금 걸친 "수시 운행" 같은 시간대, 없으면 null)이 있다. running이 있으면 그 시간대 안내를 먼저 전한다.
                    답할 때 basis 문장을 꼭 같이 전한다.
                    code가 NO_TIMETABLE이면 시간표가 없는 것이니 note를 그대로 전하고 시각을 지어내지 않는다.
                    next가 비고 running도 없으면 그 시각 뒤로 남은 차가 없다. 시내버스는 city_bus_arrival로 본다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    ShuttleTimes campusShuttle(
            McpSyncRequestContext context,
            @McpToolParam(description = "볼 날짜, YYYY-MM-DD. 없으면 오늘", required = false) String date,
            @McpToolParam(description = "이 시각 뒤 출발만, HH:mm (예: 15:00). 없으면 오늘은 지금부터", required = false) String after) {
        Credentials.require(context);
        return shuttle.next(parseDate(date), parseTime(after));
    }

    @McpTool(name = "city_bus_arrival",
            description = """
                    IT융합대학 앞 시내버스 정류장(번호 1177)에 곧 오는 버스를 돌려준다. "시내버스 언제 와?", "419번 몇 분 남았어?",
                    "집 가는 버스"처럼 시내버스를 물을 때 쓴다. 교내 셔틀은 campus_shuttle로 본다.
                    buses마다 line(노선 번호), minutes(남은 분), stops(남은 정류장 수)가 있다. 결과에 있는 버스와 숫자만 말한다.
                    code가 NOT_CONNECTED나 BUS_UNAVAILABLE이면 note를 그대로 전하고 도착 시간을 지어내지 않는다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false))
    BusArrivals cityBusArrival(McpSyncRequestContext context) {
        Credentials.require(context);
        return cityBus.now();
    }

    @McpTool(name = "cafeteria_crowd",
            description = """
                    학교 식당이 붐비는지 돌려준다. "식당 붐벼?", "지금 밥 먹으러 가도 돼?"처럼 식당 혼잡도를 물을 때 쓴다.
                    code가 NOT_CONNECTED면 아직 데이터가 없는 것이니 note를 그대로 전하고 혼잡도를 지어내지 않는다.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    CafeteriaCrowd cafeteriaCrowd(McpSyncRequestContext context) {
        Credentials.require(context);
        return cafeteria.now().orElseGet(() -> new CafeteriaCrowd("NOT_CONNECTED", List.of(), null, CAFETERIA_NOTE));
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new CoreApiException("INVALID_REQUEST", "날짜는 YYYY-MM-DD 형식이어야 해요.");
        }
    }

    private static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new CoreApiException("INVALID_REQUEST", "시각은 HH:mm 형식이어야 해요.");
        }
    }
}
