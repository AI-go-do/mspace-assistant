package kr.ac.chosun.mspace.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerApplicationTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName("MCP 서버가 뜨고 /actuator/health가 UP (배포 직후 확인용)")
    void health() {
        String body = RestClient.create("http://localhost:" + port).get().uri("/actuator/health")
                .retrieve().body(String.class);
        assertThat(body).contains("\"status\":\"UP\"");
    }
}
