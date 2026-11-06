package kr.ac.chosun.mspace.assistant.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** M1은 뼈대만. LlmClient 포트·Gemini 어댑터(#7)와 에이전트 반복(#8)은 M2 */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
