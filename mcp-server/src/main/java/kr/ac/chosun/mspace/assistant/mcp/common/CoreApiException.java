package kr.ac.chosun.mspace.assistant.mcp.common;

/**
 * 도구·확인 실행 실패. message는 모델이나 학생이 읽을 문장이라 내부 정보(주소, 토큰)를 넣지 않는다.
 *
 * @param code   코어의 오류 코드 (예: UNAUTHORIZED, RESERVATION_TIME_TAKEN). 코어가 안 줬으면 비서가 붙인 코드
 * @param status 확인 API가 화면에 돌려줄 HTTP 상태. 코어 오류면 코어의 상태를 그대로 쓴다
 */
public class CoreApiException extends RuntimeException {

    private final String code;
    private final int status;

    public CoreApiException(String code, String message) {
        this(code, message, 400);
    }

    public CoreApiException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
