package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.time.LocalDate;
import java.util.List;

/**
 * 예보 서버 응답에서 쓰는 값만.
 *
 * @param nowTemperature 지금 기온(℃). 모르면 null
 * @param nowCode        지금 날씨 코드(WMO). 모르면 null
 * @param days           날짜별 예보 (오늘부터)
 */
public record Forecast(Double nowTemperature, Integer nowCode, List<Day> days) {

    /**
     * @param code       그날 대표 날씨 코드(WMO)
     * @param rainChance 그날 가장 높은 비 올 확률(%). 모르면 null
     */
    public record Day(LocalDate date, Integer code, Double minTemperature, Double maxTemperature, Integer rainChance) {
    }
}
