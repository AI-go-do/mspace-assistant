package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory.InMemoryTeamStore;
import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.Team;
import kr.ac.chosun.mspace.assistant.agent.chat.application.TeamStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 내 팀 API (FR-AGT-105) */
class TeamControllerTest {

    static final String ME = "Bearer me";
    static final String OTHER = "Bearer other";
    static final String TWO = "{\"members\":[{\"studentId\":\"99999002\",\"name\":\"김*영\"},{\"studentId\":\"99999003\",\"name\":\"박*웅\"}]}";

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties(null, List.of("Authorization"), 8, null, null, null, 0, 0);
        mvc = MockMvcBuilders.standaloneSetup(new TeamController(new InMemoryTeamStore(), props)).build();
    }

    @Test
    @DisplayName("팀을 저장하면 내 목록에만 보이고, 같은 이름으로 다시 저장하면 바뀌며, 지우면 사라진다")
    void saveReplaceDelete() throws Exception {
        // given / when
        mvc.perform(put("/api/agent/teams/팀 A").header("Authorization", ME).contentType(MediaType.APPLICATION_JSON).content(TWO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams[0].name").value("팀 A"))
                .andExpect(jsonPath("$.teams[0].members.length()").value(2));
        mvc.perform(put("/api/agent/teams/팀 A").header("Authorization", ME).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"members\":[{\"studentId\":\"99999004\",\"name\":\"이*연\"}]}"))
                .andExpect(jsonPath("$.teams.length()").value(1))
                .andExpect(jsonPath("$.teams[0].members[0].studentId").value("99999004"));

        // then
        mvc.perform(get("/api/agent/teams").header("Authorization", OTHER)).andExpect(jsonPath("$.teams.length()").value(0));
        mvc.perform(delete("/api/agent/teams/팀 A").header("Authorization", ME)).andExpect(status().isNoContent());
        mvc.perform(get("/api/agent/teams").header("Authorization", ME)).andExpect(jsonPath("$.teams.length()").value(0));
    }

    @Test
    @DisplayName("빈 멤버·중복 학번·긴 이름·숫자 든 이름·null 멤버는 400 INVALID_TEAM, 로그인 안 하면 401")
    void invalid() throws Exception {
        mvc.perform(put("/api/agent/teams/팀").header("Authorization", ME).contentType(MediaType.APPLICATION_JSON).content("{\"members\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TEAM"));
        mvc.perform(put("/api/agent/teams/팀").header("Authorization", ME).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"members\":[{\"studentId\":\"99999002\"},{\"studentId\":\"99999002\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/agent/teams/열세글자가넘는아주긴팀이름이에요").header("Authorization", ME).contentType(MediaType.APPLICATION_JSON).content(TWO))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/agent/teams/팀20231234").header("Authorization", ME).contentType(MediaType.APPLICATION_JSON).content(TWO))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TEAM"));
        mvc.perform(put("/api/agent/teams/팀").header("Authorization", ME).contentType(MediaType.APPLICATION_JSON).content("{\"members\":[null]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TEAM"));
        mvc.perform(get("/api/agent/teams")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("팀 저장소를 못 읽으면 503 MEMORY_UNAVAILABLE이고 팀 문구로 알린다(빈 목록으로 덮어쓰지 않음)")
    void storeDown() throws Exception {
        // given
        TeamStore down = new TeamStore() {
            @Override
            public List<Team> load(String memberKey) {
                return List.of();
            }

            @Override
            public List<Team> loadStrict(String memberKey) {
                throw new ConversationMemory.Unavailable();
            }

            @Override
            public void save(String memberKey, List<Team> teams) {
                throw new AssertionError("못 읽었으면 저장하지 않는다");
            }
        };
        AgentProperties props = new AgentProperties(null, List.of("Authorization"), 8, null, null, null, 0, 0);
        MockMvc downMvc = MockMvcBuilders.standaloneSetup(new TeamController(down, props)).build();

        // when·then
        downMvc.perform(put("/api/agent/teams/팀 A").header("Authorization", ME).contentType(MediaType.APPLICATION_JSON).content(TWO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MEMORY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("팀 정보를 저장하지 못했어요. 잠시 후 다시 시도해 주세요."));
    }

    @Test
    @DisplayName("팀은 10개까지, 11번째는 400 TOO_MANY_TEAMS")
    void tooMany() throws Exception {
        for (int i = 1; i <= 10; i++) {
            mvc.perform(put("/api/agent/teams/팀" + i).header("Authorization", ME).contentType(MediaType.APPLICATION_JSON).content(TWO))
                    .andExpect(status().isOk());
        }
        mvc.perform(put("/api/agent/teams/팀11").header("Authorization", ME).contentType(MediaType.APPLICATION_JSON).content(TWO))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY_TEAMS"));
    }
}
