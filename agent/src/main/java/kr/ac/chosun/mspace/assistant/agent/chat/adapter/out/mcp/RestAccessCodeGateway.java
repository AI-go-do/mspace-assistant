package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.mcp;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AccessCodeGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConfirmationGateway.Relayed;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** mcp-server의 GET /api/reservations/{id}/access-code를 학생 헤더로 부른다. 본문(번호)은 로그에 남기지 않는다 */
@Component
class RestAccessCodeGateway implements AccessCodeGateway {

    static final String UNAVAILABLE =
            "{\"code\":\"TOOLS_UNAVAILABLE\",\"message\":\"입실 번호를 불러오지 못했어요. 예약 화면에서 확인해 주세요.\"}";

    private final RestClient mcpServer;

    RestAccessCodeGateway(AgentProperties props) {
        // HTTP/1.1 고정: TS-04와 같은 이유 (h2c 업그레이드 끊김)
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(props.timeout()).build());
        factory.setReadTimeout(props.timeout());
        this.mcpServer = RestClient.builder().baseUrl(props.mcpServerUrl()).requestFactory(factory).build();
    }

    @Override
    public Relayed accessCode(long reservationId, UserHeaders user) {
        try {
            return mcpServer.get().uri("/api/reservations/{id}/access-code", reservationId)
                    .headers(h -> user.values().forEach(h::set))
                    .exchange((req, res) -> new Relayed(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            return new Relayed(503, UNAVAILABLE);
        }
    }
}
