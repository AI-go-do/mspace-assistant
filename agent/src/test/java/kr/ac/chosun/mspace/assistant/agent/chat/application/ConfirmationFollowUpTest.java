package kr.ac.chosun.mspace.assistant.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** 확인 뒤: 결과를 대화 맥락에 남기고 빠른 답장을 붙인다. 입실 번호는 맥락에 안 들어간다 */
class ConfirmationFollowUpTest {

    static final UserHeaders ME = new UserHeaders(Map.of("Authorization", "Bearer t"));
    final ConversationMemory memory = mock(ConversationMemory.class);
    final JsonMapper json = JsonMapper.builder().build();
    final ConfirmationFollowUp followUp = new ConfirmationFollowUp(memory, json);

    @Test
    @DisplayName("예약 성공(201): 화면에는 입실 번호 그대로 + suggestions, 미룬 글에는 [확인] 누름 + '예약했어요. (예약 7번, BOOKED)'만 (번호 없음). 대화를 읽어 덮지 않는다")
    void createRecorded() {
        // given
        String body = "{\"action\":\"CREATE\",\"reservationId\":7,\"status\":\"BOOKED\",\"accessCode\":\"483920\",\"replayed\":false,\"message\":\"예약했어요.\"}";

        // when
        String out = followUp.remember(ME, new ConfirmationGateway.Relayed(201, body));

        // then
        var node = json.readTree(out);
        assertThat(node.path("accessCode").asString()).isEqualTo("483920");
        assertThat(node.path("suggestions")).extracting(n -> n.asString()).containsExactly("입실 번호 보여 줘", "내 예약 보여 줘");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> saved = ArgumentCaptor.forClass(List.class);
        verify(memory).addNote(anyString(), saved.capture());
        assertThat(saved.getValue()).containsExactly(new LlmMessage.User(ConfirmationFollowUp.PRESSED),
                new LlmMessage.Assistant("예약했어요. (예약 7번, BOOKED)", List.of()));
        assertThat(saved.getValue().toString()).doesNotContain("483920");
        verify(memory, org.mockito.Mockito.never()).load(anyString());
        verify(memory, org.mockito.Mockito.never()).save(anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("실패(409 RESERVATION_TIME_TAKEN): suggestions 없이 본문 그대로, 미룬 글에는 '확인을 처리하지 못했어요: …'")
    void failureRecorded() {
        // given
        String body = "{\"code\":\"RESERVATION_TIME_TAKEN\",\"message\":\"그 시간에 다른 예약이 있어요.\"}";

        // when
        String out = followUp.remember(ME, new ConfirmationGateway.Relayed(409, body));

        // then
        assertThat(json.readTree(out).has("suggestions")).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> saved = ArgumentCaptor.forClass(List.class);
        verify(memory).addNote(anyString(), saved.capture());
        assertThat(saved.getValue()).containsExactly(new LlmMessage.User(ConfirmationFollowUp.PRESSED),
                new LlmMessage.Assistant(ConfirmationFollowUp.FAILED_PREFIX + "그 시간에 다른 예약이 있어요.", List.of()));
    }

    @Test
    @DisplayName("맥락 저장이 실패해도 확인 결과는 그대로 돌려준다. 로그인 없음·본문 해석 불가면 맥락을 건드리지 않는다")
    void memoryFailureAndOddBodies() {
        // given
        doThrow(new RuntimeException("redis down")).when(memory).addNote(anyString(), org.mockito.ArgumentMatchers.anyList());
        String body = "{\"action\":\"CANCEL\",\"reservationId\":7,\"status\":\"CANCELED\",\"accessCode\":null,\"replayed\":false,\"message\":\"취소했어요.\"}";

        // when
        String out = followUp.remember(ME, new ConfirmationGateway.Relayed(200, body));
        String anonymous = followUp.remember(new UserHeaders(Map.of()), new ConfirmationGateway.Relayed(401, "{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인\"}"));
        String odd = followUp.remember(ME, new ConfirmationGateway.Relayed(502, "<html>bad gateway</html>"));

        // then
        assertThat(json.readTree(out).path("suggestions")).extracting(n -> n.asString()).containsExactly("다른 시간 찾아 줘", "지금 빈 방 있어?");
        assertThat(json.readTree(anonymous).has("suggestions")).isFalse();
        assertThat(odd).isEqualTo("<html>bad gateway</html>");
        verify(memory, org.mockito.Mockito.times(1)).addNote(anyString(), org.mockito.ArgumentMatchers.anyList()); // 로그인 없는 요청·해석 불가 본문은 안 남긴다
    }

    @Test
    @DisplayName("액션별 빠른 답장: 즉시 시작·연장은 입실 번호, 퇴실은 내 예약·빈 방, 모르는 액션은 없음")
    void suggestionsByAction() {
        assertThat(ConfirmationFollowUp.suggestions("START_NOW")).containsExactly("입실 번호 보여 줘", "연장할 수 있어?");
        assertThat(ConfirmationFollowUp.suggestions("CONTINUE")).containsExactly("입실 번호 보여 줘", "내 예약 보여 줘");
        assertThat(ConfirmationFollowUp.suggestions("CHECKOUT")).containsExactly("내 예약 보여 줘", "지금 빈 방 있어?");
        assertThat(ConfirmationFollowUp.suggestions("")).isEmpty();
        verifyNoInteractions(memory);
    }
}
