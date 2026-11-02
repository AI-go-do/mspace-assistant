package kr.ac.chosun.mspace.assistant.mcp.common;

/**
 * 예약 코어 호출 실패. message는 모델이 읽고 학생에게 전할 문장이라 내부 정보(주소, 토큰)를 넣지 않는다.
 *
 * @param code 코어의 오류 코드 (예: UNAUTHORIZED, RESERVATION_TIME_TAKEN). 코어가 안 줬으면 비서가 붙인 코드
 */
public class CoreApiException extends RuntimeException {

    private final String code;

    public CoreApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
