package kr.ac.chosun.mspace.assistant.agent.chat.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaTest {

    @Test
    @DisplayName("채팅 말에서 성향 바꾸기를 알아듣는다: 조용/수동→QUIET, 잔소리→NAGGING, 능동/알아서→ACTIVE. 그냥 질문은 아님")
    void requested() {
        assertThat(Persona.requested("잔소리 모드로 해 줘")).contains(Persona.NAGGING);
        assertThat(Persona.requested("조용한 비서로 바꿔")).contains(Persona.QUIET);
        assertThat(Persona.requested("수동 성향으로")).contains(Persona.QUIET);
        assertThat(Persona.requested("알아서 하는 스타일로 바꿔 줘")).contains(Persona.ACTIVE);
        assertThat(Persona.requested("능동 모드")).contains(Persona.ACTIVE);
        assertThat(Persona.requested("내일 오후 2시 빈방")).isEmpty();
        assertThat(Persona.requested("잔소리 좀 그만")).as("모드·성향 같은 말이 없으면 안 바꿈").isEmpty();
        assertThat(Persona.requested(null)).isEmpty();
    }

    @Test
    @DisplayName("API 값은 대소문자 상관없이 읽고, 모르는 값은 비어 있다")
    void parse() {
        assertThat(Persona.parse("quiet")).contains(Persona.QUIET);
        assertThat(Persona.parse(" NAGGING ")).contains(Persona.NAGGING);
        assertThat(Persona.parse("loud")).isEmpty();
        assertThat(Persona.parse("")).isEmpty();
        assertThat(Persona.DEFAULT).isEqualTo(Persona.ACTIVE);
        assertThat(Persona.all()).hasSize(3);
    }
}
