package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kr.ac.chosun.mspace.assistant.agent.chat.application.Persona;
import kr.ac.chosun.mspace.assistant.agent.chat.application.PersonaStore;

/** 메모리 보관 (기본, agent 1대. 재시작하면 기본 성향으로) */
public class InMemoryPersonaStore implements PersonaStore {

    private final Map<String, Persona> personas = new ConcurrentHashMap<>();

    @Override
    public Persona load(String memberKey) {
        return personas.getOrDefault(memberKey, Persona.DEFAULT);
    }

    @Override
    public void save(String memberKey, Persona persona) {
        personas.put(memberKey, persona);
    }
}
