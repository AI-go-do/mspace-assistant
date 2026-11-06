package kr.ac.chosun.mspace.assistant.agent.chat.adapter.out.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConversationMemory;
import kr.ac.chosun.mspace.assistant.agent.chat.application.MemoryProperties;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;

/**
 * 메모리 보관 (기본). agent 인스턴스 1대, 재시작하면 사라진다. 여러 대이거나 재배포 뒤에도 잇고 싶으면 Redis 어댑터로 바꾼다.
 * TTL은 읽고 쓸 때마다 훑어서 지운다 (학생 수가 수백 명이라 매번 훑어도 된다).
 */
public class InMemoryConversationMemory implements ConversationMemory {

    private record Entry(List<LlmMessage> conversation, Instant savedAt) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    /** 미룬 글(확인 결과). 학생별 목록을 compute로 통째로 바꿔 끼우기·꺼내기가 서로 섞이지 않는다 */
    private final Map<String, Entry> notes = new ConcurrentHashMap<>();
    private final MemoryProperties props;
    private final Clock clock;

    public InMemoryConversationMemory(MemoryProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @Override
    public List<LlmMessage> load(String memberKey) {
        sweep();
        Entry entry = store.get(memberKey);
        return entry == null ? List.of() : entry.conversation();
    }

    @Override
    public void save(String memberKey, List<LlmMessage> conversation) {
        sweep();
        store.put(memberKey, new Entry(List.copyOf(conversation), clock.instant()));
    }

    @Override
    public void clear(String memberKey) {
        store.remove(memberKey);
        notes.remove(memberKey);
    }

    @Override
    public void addNote(String memberKey, List<LlmMessage> note) {
        sweep();
        notes.compute(memberKey, (k, old) -> {
            List<LlmMessage> merged = new java.util.ArrayList<>(old == null ? List.of() : old.conversation());
            merged.addAll(note);
            return new Entry(List.copyOf(merged), clock.instant());
        });
    }

    @Override
    public List<LlmMessage> takeNotes(String memberKey) {
        sweep();
        Entry taken = notes.remove(memberKey);
        return taken == null ? List.of() : taken.conversation();
    }

    private void sweep() {
        Instant cutoff = clock.instant().minus(props.ttl());
        store.entrySet().removeIf(e -> e.getValue().savedAt().isBefore(cutoff));
        notes.entrySet().removeIf(e -> e.getValue().savedAt().isBefore(cutoff));
    }

    int size() {
        return store.size();
    }
}
