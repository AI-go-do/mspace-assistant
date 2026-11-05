package kr.ac.chosun.mspace.assistant.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import kr.ac.chosun.mspace.assistant.agent.llm.application.ImageReader;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 시간표 캡처 읽기: 모델 답을 믿지 않고 요일·시각을 검사한다. 모델은 가짜(ImageReader mock) */
class ReadTimetableImageTest {

    final ImageReader reader = mock(ImageReader.class);
    final ReadTimetableImage service = new ReadTimetableImage(reader, JsonMapper.builder().build());
    final byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G'};

    @Test
    @DisplayName("정상: 모델이 준 JSON을 classes 초안으로 바꾸고, 지시문에 이름·학번 금지가 들어간다")
    void parsesClasses() {
        // given
        when(reader.read(anyString(), eq("image/png"), any())).thenReturn("""
                {"classes":[
                  {"dayOfWeek":"mon","startTime":"9:00","endTime":"10:30","name":"  자료구조 "},
                  {"dayOfWeek":"WED","startTime":"13:00","endTime":"15:00","name":"운영체제"}
                ]}""");

        // when
        ReadTimetableImage.Draft draft = service.read("image/png", png);

        // then
        assertThat(draft.classes()).containsExactly(
                new ReadTimetableImage.ClassDraft("MON", "09:00", "10:30", "자료구조"),
                new ReadTimetableImage.ClassDraft("WED", "13:00", "15:00", "운영체제"));
        assertThat(draft.dropped()).isZero();
        assertThat(draft.note()).contains("확인");
        verify(reader).read(org.mockito.ArgumentMatchers.contains("학번, 사람 이름은 절대 적지 않는다"), eq("image/png"), eq(png));
    }

    @Test
    @DisplayName("모델 답 중 요일·시각이 틀린 줄과 중복은 버리고 dropped로 센다. 코드 펜스도 벗긴다")
    void dropsInvalidRows() {
        // given
        when(reader.read(anyString(), anyString(), any())).thenReturn("""
                ```json
                {"classes":[
                  {"dayOfWeek":"월","startTime":"09:00","endTime":"10:30","name":"자료구조"},
                  {"dayOfWeek":"TUE","startTime":"11:00","endTime":"10:00","name":"거꾸로"},
                  {"dayOfWeek":"TUE","startTime":"25:00","endTime":"26:00","name":"없는 시각"},
                  {"dayOfWeek":"FRI","startTime":"10:00","endTime":"11:00","name":""},
                  {"dayOfWeek":"FRI","startTime":"10:00","endTime":"11:00","name":"알고리즘"},
                  {"dayOfWeek":"FRI","startTime":"10:00","endTime":"11:00","name":"알고리즘"}
                ]}
                ```""");

        // when
        ReadTimetableImage.Draft draft = service.read("image/jpeg", png);

        // then
        assertThat(draft.classes()).containsExactly(new ReadTimetableImage.ClassDraft("FRI", "10:00", "11:00", "알고리즘"));
        assertThat(draft.dropped()).isEqualTo(4);
        assertThat(draft.note()).contains("4개");
    }

    @Test
    @DisplayName("모델이 JSON이 아닌 글을 주면 빈 초안 + 다시 올리라는 안내 (오류 아님)")
    void notJson() {
        when(reader.read(anyString(), anyString(), any())).thenReturn("죄송해요, 시간표가 보이지 않아요.");

        ReadTimetableImage.Draft draft = service.read("image/webp", png);

        assertThat(draft.classes()).isEmpty();
        assertThat(draft.note()).contains("직접 입력");
    }

    @Test
    @DisplayName("형식·크기 검사: PNG/JPEG/WebP가 아니거나 5MB를 넘으면 모델을 부르지 않고 InvalidImage")
    void rejectsBadInput() {
        assertThatThrownBy(() -> service.read("application/pdf", png)).isInstanceOf(ReadTimetableImage.InvalidImage.class);
        assertThatThrownBy(() -> service.read("image/png", new byte[0])).isInstanceOf(ReadTimetableImage.InvalidImage.class);
        assertThatThrownBy(() -> service.read("image/png", new byte[ReadTimetableImage.MAX_BYTES + 1]))
                .isInstanceOf(ReadTimetableImage.InvalidImage.class).hasMessageContaining("5MB");
        verifyNoInteractions(reader);
    }

    @Test
    @DisplayName("모델 장애(LlmException)는 그대로 올린다 → 컨트롤러가 503/429로 바꾼다")
    void llmFailurePropagates() {
        when(reader.read(anyString(), anyString(), any())).thenThrow(new LlmException("LLM_UNAVAILABLE", "사진을 읽지 못했어요."));

        assertThatThrownBy(() -> service.read("image/png", png)).isInstanceOf(LlmException.class);
    }

    @Test
    @DisplayName("Ollama 등 그림을 못 읽는 모델은 LLM_NOT_CONFIGURED")
    void unsupportedReader() {
        ReadTimetableImage ollama = new ReadTimetableImage(ImageReader.UNSUPPORTED, JsonMapper.builder().build());

        assertThatThrownBy(() -> ollama.read("image/png", png)).isInstanceOf(LlmException.class)
                .extracting("code").isEqualTo("LLM_NOT_CONFIGURED");
    }
}
