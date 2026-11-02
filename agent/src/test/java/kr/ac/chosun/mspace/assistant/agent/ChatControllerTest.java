package kr.ac.chosun.mspace.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 채팅 API 입력 검사: 잘못된 대화 기록은 모델·MCP를 부르기 전에 400 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatControllerTest {

    @LocalServerPort
    int port;

    HttpResponse<String> chat(String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/agent/chat"))
                .header("Content-Type", "application/json").header("X-User-Id", "1")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"message\":\"내일 빈방\",\"history\":[null]}",
            "{\"message\":\"내일 빈방\",\"history\":[{\"role\":\"user\"}]}"})
    @DisplayName("대화 기록에 빈 항목이나 글 없는 항목이 있으면 400 INVALID_REQUEST")
    void rejectsBrokenHistory(String body) throws Exception {
        // when
        HttpResponse<String> response = chat(body);

        // then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("INVALID_REQUEST");
    }

    @Test
    @DisplayName("말이 비어 있으면 400 INVALID_REQUEST")
    void rejectsBlankMessage() throws Exception {
        // given
        String body = "{\"message\":\" \"}";

        // when
        HttpResponse<String> response = chat(body);

        // then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("INVALID_REQUEST");
    }
}
