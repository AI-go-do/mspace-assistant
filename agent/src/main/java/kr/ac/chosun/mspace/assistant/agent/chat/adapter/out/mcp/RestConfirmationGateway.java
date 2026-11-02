package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.mcp;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConfirmationGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** mcp-server의 POST /api/confirmations/{token}을 학생 헤더로 부른다 */
@Component
class RestConfirmationGateway implements ConfirmationGateway {

    static final String UNAVAILABLE =
            "{\"code\":\"TOOLS_UNAVAILABLE\",\"message\":\"예약 도구에 연결하지 못했어요. 내 예약에서 결과를 확인해 주세요.\"}";

    private final RestClient mcpServer;

    RestConfirmationGateway(AgentProperties props) {
        // HTTP/1.1 고정: TS-04와 같은 이유 (h2c 업그레이드 끊김)
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(props.timeout()).build());
        factory.setReadTimeout(props.timeout().multipliedBy(3)); // 예약 생성은 재시도까지 기다린다
        this.mcpServer = RestClient.builder().baseUrl(props.mcpServerUrl()).requestFactory(factory).build();
    }

    @Override
    public Relayed confirm(String token, UserHeaders user, String body) {
        try {
            return mcpServer.post().uri("/api/confirmations/{token}", token)
                    .headers(h -> user.values().forEach(h::set))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body == null ? "{}" : body)
                    .exchange((req, res) -> new Relayed(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            return new Relayed(503, UNAVAILABLE);
        }
    }
}
