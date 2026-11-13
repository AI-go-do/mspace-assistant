package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.time.Clock;
import java.time.LocalDate;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.SeoulTime;
import org.springframework.stereotype.Service;

/** 오늘·내일 학교 날씨. 예보 서버가 안 되면 지어내지 않고 오류로 돌려준다 */
@Service
public class CampusWeather {

    static final String NOTE = "Open-Meteo 예보 기준이에요.";
    static final String UNAVAILABLE = "날씨를 불러오지 못했어요. 잠시 후 다시 물어봐 주세요.";

    private final WeatherPort weather;
    private final CampusProperties props;
    private final Clock clock;

    public CampusWeather(WeatherPort weather, CampusProperties props, Clock clock) {
        this.weather = weather;
        this.props = props;
        this.clock = clock;
    }

    /** @param date null이면 오늘. 오늘·내일만 된다 */
    public WeatherReport of(LocalDate date) {
        if (!props.weather().enabled()) {
            throw new CoreApiException("WEATHER_OFF", "지금은 날씨를 볼 수 없어요.", 503);
        }
        LocalDate today = LocalDate.now(clock.withZone(SeoulTime.ZONE));
        LocalDate day = date == null ? today : date;
        if (day.isBefore(today) || day.isAfter(today.plusDays(1))) {
            throw new CoreApiException("INVALID_REQUEST", "날씨는 오늘과 내일만 볼 수 있어요.");
        }
        Forecast forecast = weather.forecast()
                .orElseThrow(() -> new CoreApiException("WEATHER_UNAVAILABLE", UNAVAILABLE, 503));
        Forecast.Day d = forecast.days().stream().filter(x -> day.equals(x.date())).findFirst()
                .orElseThrow(() -> new CoreApiException("WEATHER_UNAVAILABLE", UNAVAILABLE, 503));
        boolean isToday = day.equals(today);
        boolean umbrella = d.rainChance() != null && d.rainChance() >= props.weather().umbrellaPercent();
        return new WeatherReport(day, props.weather().place(), WeatherCode.korean(d.code()), d.minTemperature(),
                d.maxTemperature(), d.rainChance(), umbrella, isToday ? forecast.nowTemperature() : null,
                isToday ? WeatherCode.korean(forecast.nowCode()) : null, NOTE);
    }
}
