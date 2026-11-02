package kr.ac.chosun.mspace.assistant.mcp.common;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예약 코어 호출 공통부: 타임아웃, 사용자 헤더 전달, 오류 본문 {code, message} 살리기, 로그.
 * 로그에는 경로·걸린 시간·성공 여부만 남긴다 (CLAUDE.md 4장). 헤더 값은 남기지 않는다.
 */
@Component
public class CoreHttp {

    /** 코어에 닿지 못함(꺼짐·타임아웃). 쓰기 요청이면 처리됐는지 모른다는 뜻이다 */
    public static final String UNAVAILABLE = "CORE_UNAVAILABLE";

    /** 응답은 왔지만 읽지 못함. 쓰기 요청이면 처리됐을 수 있다 */
    public static final String BAD_RESPONSE = "CORE_BAD_RESPONSE";

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
        return callEntity(label, credential, request, type).getBody();
    }

    /** call과 같고, 상태 코드도 필요할 때 (예약 생성: 201 새로 만듦 / 200 같은 멱등 키 재전송) */
    public <T> ResponseEntity<T> callEntity(String label, UserCredential credential,
                                            Function<RestClient, RestClient.RequestHeadersSpec<?>> request,
                                            Class<T> type) {
        long started = System.nanoTime();
        boolean ok = false;
        try {
            ResponseEntity<T> entity = request.apply(rest)
                    .headers(h -> credential.headers().forEach(h::set))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw toException(res.getStatusCode(), readBody(res));
                    })
                    .toEntity(type);
            ok = true;
            return entity;
        } catch (ResourceAccessException e) {
            // 코어가 꺼졌거나 느림. 주소·원인은 로그에만
            log.warn("core 호출 실패 {} cause={}", label, e.getClass().getSimpleName());
            throw new CoreApiException(UNAVAILABLE, "예약 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.", 503);
        } catch (RestClientException e) {
            // 응답은 왔는데 읽지 못함(본문 모양이 다름 등). 쓰기 요청이면 처리됐을 수 있다
            log.warn("core 응답 해석 실패 {} cause={}", label, e.getClass().getSimpleName());
            throw new CoreApiException(BAD_RESPONSE, "예약 서버 응답을 확인하지 못했어요. 예약 상태를 먼저 확인해 주세요.", 502);
        } finally {
            log.info("core {} ok={} {}ms", label, ok, (System.nanoTime() - started) / 1_000_000);
        }
    }

    /** 코어 오류 본문 {code, message}를 그대로 살린다. 모양이 다르면 상태 코드로 대신 */
    private CoreApiException toException(HttpStatusCode status, byte[] body) {
        // 게이트웨이 오류는 본문보다 먼저 본다: 앞단 프록시가 어떤 본문을 주든 core가 처리했는지 모르므로 '닿지 못함'
        if (status.value() == 502 || status.value() == 503 || status.value() == 504) {
            return new CoreApiException(UNAVAILABLE, "예약 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.", 503);
        }
        try {
            CoreError error = json.readValue(body, CoreError.class);
            if (error.code() != null && error.message() != null) {
                return new CoreApiException(error.code(), error.message(), status.value());
            }
        } catch (RuntimeException ignored) {
            // 본문이 JSON이 아님 (프록시 오류 페이지 등)
        }
        return switch (status.value()) {
            case 401 -> new CoreApiException("UNAUTHORIZED", "로그인이 필요합니다", 401);
            case 403 -> new CoreApiException("FORBIDDEN", "이 기능을 사용할 권한이 없습니다", 403);
            // 본문을 못 읽어도 받은 상태 코드(400·409 등)는 그대로 둔다
            default -> new CoreApiException("CORE_ERROR", "예약 서버에서 오류가 났어요. 잠시 후 다시 시도해 주세요.",
                    status.value());
        };
    }

    record CoreError(String code, String message) {
    }

    /** 오류 본문을 읽다 끊겨도 이미 받은 상태 코드(409·401 등)는 살린다. 본문 없이 상태 코드로 분류된다 */
    private static byte[] readBody(ClientHttpResponse res) {
        try {
            return res.getBody().readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
