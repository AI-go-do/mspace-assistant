package kr.ac.chosun.mspace.assistant.mcp.common;

import java.net.http.HttpClient;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예약 코어 호출 공통부: 타임아웃, 사용자 헤더 전달, 오류 본문 {code, message} 살리기, 로그.
 * 로그에는 경로·걸린 시간·성공 여부만 남긴다 (CLAUDE.md 4장). 헤더 값은 남기지 않는다.
 */
@Component
public class CoreHttp {

    private static final Logger log = LoggerFactory.getLogger(CoreHttp.class);

    private final RestClient rest;
    private final JsonMapper json;

    CoreHttp(CoreProperties props, JsonMapper json) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.timeout()).build());
        factory.setReadTimeout(props.timeout());
        // Boot 4는 RestClient.Builder 자동 설정이 별도 모듈(spring-boot-restclient)이라 직접 만든다
        this.rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
        this.json = json;
    }

    /**
     * 코어를 한 번 부른다. call 안에서 rest.get()/post()로 요청을 만들고, 이 메서드가 헤더·오류·로그를 맡는다.
     *
     * @param label 로그용 이름 (예: "GET /api/reservations/me"). 사용자 값을 넣지 않는다
     */
    public <T> T call(String label, UserCredential credential,
                      Function<RestClient, RestClient.RequestHeadersSpec<?>> request, Class<T> type) {
        long started = System.nanoTime();
        boolean ok = false;
        try {
            T body = request.apply(rest)
                    .headers(h -> credential.headers().forEach(h::set))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw toException(res.getStatusCode(), res.getBody().readAllBytes());
                    })
                    .body(type);
            ok = true;
            return body;
        } catch (ResourceAccessException e) {
            // 코어가 꺼졌거나 느림. 주소·원인은 로그에만
            log.warn("core 호출 실패 {} cause={}", label, e.getClass().getSimpleName());
            throw new CoreApiException("CORE_UNAVAILABLE", "예약 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        } finally {
            log.info("core {} ok={} {}ms", label, ok, (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** 코어 오류 본문 {code, message}를 그대로 살린다. 모양이 다르면 상태 코드로 대신 */
    private CoreApiException toException(HttpStatusCode status, byte[] body) {
        try {
            CoreError error = json.readValue(body, CoreError.class);
            if (error.code() != null && error.message() != null) {
                return new CoreApiException(error.code(), error.message());
            }
        } catch (RuntimeException ignored) {
            // 본문이 JSON이 아님 (프록시 오류 페이지 등)
        }
        return switch (status.value()) {
            case 401 -> new CoreApiException("UNAUTHORIZED", "로그인이 필요합니다");
            case 403 -> new CoreApiException("FORBIDDEN", "이 기능을 사용할 권한이 없습니다");
            default -> new CoreApiException("CORE_ERROR", "예약 서버에서 오류가 났어요. 잠시 후 다시 시도해 주세요.");
        };
    }

    record CoreError(String code, String message) {
    }
}
