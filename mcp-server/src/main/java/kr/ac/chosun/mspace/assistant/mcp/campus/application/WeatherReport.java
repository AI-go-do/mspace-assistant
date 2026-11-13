package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.time.LocalDate;

/**
 * campus_weather 도구 결과. 문장은 모델이 만들되 숫자는 여기 있는 것만 쓴다.
 *
 * @param summary        그날 날씨 한 단어 (맑음, 흐림, 비 …)
 * @param rainChance     비 올 확률(%). 모르면 null
 * @param umbrella       비 올 확률이 기준 이상이면 true
 * @param nowTemperature 오늘이면 지금 기온, 아니면 null
 * @param note           학생에게 그대로 전할 출처 한 줄
 */
public record WeatherReport(LocalDate date, String place, String summary, Double minTemperature, Double maxTemperature,
                            Integer rainChance, boolean umbrella, Double nowTemperature, String nowSummary, String note) {
}
