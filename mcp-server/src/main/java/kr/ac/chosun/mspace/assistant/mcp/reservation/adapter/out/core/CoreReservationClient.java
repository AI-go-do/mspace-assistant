package kr.ac.chosun.mspace.assistant.mcp.reservation.adapter.out.core;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreApiException;
import kr.ac.chosun.mspace.assistant.mcp.common.CoreProperties;
import kr.ac.chosun.mspace.assistant.mcp.common.UserCredential;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.MyReservation;
import kr.ac.chosun.mspace.assistant.mcp.reservation.application.ReservationQueryPort;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 예약 코어 REST 호출. 코어 응답 모양(CoreReservation)은 docs/도구-명세.md 2장의 가정이다.
 * 코어에 내 예약 API가 나오면 그 응답에 맞춰 이 레코드만 고친다.
 */
@Component
class CoreReservationClient implements ReservationQueryPort {

    private static final Logger log = LoggerFactory.getLogger(CoreReservationClient.class);

    private final RestClient rest;
    private final JsonMapper json;

    CoreReservationClient(CoreProperties props, JsonMapper json) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.timeout()).build());
        factory.setReadTimeout(props.timeout());
        // Boot 4는 RestClient.Builder 자동 설정이 별도 모듈(spring-boot-restclient)이라 직접 만든다
        this.rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
        this.json = json;
    }

    @Override
    public List<MyReservation> findMine(UserCredential credential) {
        long started = System.nanoTime();
        try {
            CoreReservation[] body = rest.get().uri("/api/reservations/me")
                    .headers(h -> credential.headers().forEach(h::set))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw toException(res.getStatusCode(), res.getBody().readAllBytes());
                    })
                    .body(CoreReservation[].class);
            return body == null ? List.of() : List.of(body).stream().map(CoreReservation::toMine).toList();
        } catch (ResourceAccessException e) {
            // 코어가 꺼졌거나 느림. 주소·원인은 로그에만
            log.warn("core 호출 실패 path=/api/reservations/me cause={}", e.getClass().getSimpleName());
            throw new CoreApiException("CORE_UNAVAILABLE", "예약 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        } finally {
            log.info("core GET /api/reservations/me {}ms", (System.nanoTime() - started) / 1_000_000);
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

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    record CoreError(String code, String message) {
    }

    /** 코어 응답 한 건 (가정). 모르는 필드는 무시한다 */
    record CoreReservation(long id, CoreRoom room, OffsetDateTime startAt, OffsetDateTime endAt,
                           String status, boolean owner, List<CoreParticipant> participants,
                           String accessCode) {

        MyReservation toMine() {
            int count = participants == null ? 0 : participants.size();
            return new MyReservation(id, room == null ? null : room.name(), seoul(startAt), seoul(endAt),
                    status, owner, count);
        }

        /**
         * Jackson은 읽을 때 OffsetDateTime을 UTC로 바꿔 둔다(14:00+09:00 → 05:00Z).
         * 그대로 모델에 주면 "5시 예약"이라고 답하므로 서울 시간으로 되돌린다 (docs/트러블슈팅.md TS-01).
         */
        private static OffsetDateTime seoul(OffsetDateTime t) {
            return t == null ? null : t.atZoneSameInstant(SEOUL).toOffsetDateTime();
        }
    }

    record CoreRoom(int id, String name) {
    }

    record CoreParticipant(String name, boolean owner) {
    }
}
