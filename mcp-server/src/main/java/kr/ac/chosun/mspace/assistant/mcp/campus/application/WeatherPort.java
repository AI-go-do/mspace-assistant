package kr.ac.chosun.mspace.assistant.mcp.campus.application;

import java.util.Optional;

/** 날씨 예보. 구현은 adapter/out (지금은 Open-Meteo). 학생 정보는 넘기지 않는다: 학교 좌표만 보낸다 */
public interface WeatherPort {

    /** 오늘·내일 예보. 서버에 닿지 못하거나 모양이 다르면 empty */
    Optional<Forecast> forecast();
}
