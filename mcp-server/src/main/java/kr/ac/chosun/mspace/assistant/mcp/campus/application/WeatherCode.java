package kr.ac.chosun.mspace.assistant.mcp.campus.application;

/** WMO 날씨 코드 → 한국어 한 단어 (Open-Meteo 문서의 표) */
public final class WeatherCode {

    private WeatherCode() {
    }

    public static String korean(Integer code) {
        if (code == null) {
            return null;
        }
        int c = code;
        if (c == 0) {
            return "맑음";
        }
        if (c == 1 || c == 2) {
            return "구름 조금";
        }
        if (c == 3) {
            return "흐림";
        }
        if (c == 45 || c == 48) {
            return "안개";
        }
        if (c >= 51 && c <= 57) {
            return "이슬비";
        }
        if ((c >= 61 && c <= 67) || (c >= 80 && c <= 82)) {
            return "비";
        }
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) {
            return "눈";
        }
        if (c >= 95 && c <= 99) {
            return "뇌우";
        }
        return "알 수 없음";
    }
}
