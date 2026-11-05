package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.mcp;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConfirmationGateway.Relayed;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ScheduleGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** mcp-server의 /api/schedule/me를 학생 헤더로 부른다. 본문(과목명)은 로그에 남기지 않는다 */
@Component
class RestScheduleGateway implements ScheduleGateway {

    static final String UNAVAILABLE =
            "{\"code\":\"TOOLS_UNAVAILABLE\",\"message\":\"시간표를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.\"}";

    private final RestClient mcpServer;

    RestScheduleGateway(AgentProperties props) {
        // HTTP/1.1 고정: TS-04와 같은 이유 (h2c 업그레이드 끊김)
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(props.timeout()).build());
        factory.setReadTimeout(props.timeout());
        this.mcpServer = RestClient.builder().baseUrl(props.mcpServerUrl()).requestFactory(factory).build();
    }

    @Override
    public Relayed relay(String method, UserHeaders user, String body) {
        try {
            RestClient.RequestBodySpec request = mcpServer.method(HttpMethod.valueOf(method)).uri("/api/schedule/me")
                    .headers(h -> user.values().forEach(h::set));
            if (body != null) {
                request = request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange((req, res) -> new Relayed(res.getStatusCode().value(),
                    new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException e) { // 연결 실패·끊김·본문 읽기 실패 모두 503
            return new Relayed(503, UNAVAILABLE);
        }
    }
}
