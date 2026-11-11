package kr.ac.chosun.mspace.assistant.agent.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.UnaryOperator;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmClient;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmException;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmMessage;
import kr.ac.chosun.mspace.assistant.agent.llm.application.LlmReply;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolCall;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ToolSpec;

/**
 * 진짜 모델 앞에 끼워 호출마다 걸린 시간·토큰·도구 호출(인자 포함)을 적는다. ChatResult에는 도구 이름만 있어 인자를 볼 수 없어서다.
 * 무료 한도(분당 요청 수)를 넘지 않게 호출 사이를 띄우고, 지시문 실험이면 지시문을 바꿔 보낸다.
 */
final class RecordingLlm implements LlmClient {

    record Call(long millis, LlmReply.Usage usage, List<ToolCall> toolCalls, String errorCode) {
    }

    private final LlmClient delegate;
    private final long minIntervalMillis;
    private final UnaryOperator<String> prompt;
    private final List<Call> calls = new ArrayList<>();
    private String lastSystem;
    private long lastStartedAt;

    /**
     * @param prompt 지시문 바꾸기 (그대로 쓰면 UnaryOperator.identity()). 실험할 지시문 파일을 코드에 넣기 전에 잰다
     */
    RecordingLlm(LlmClient delegate, long minIntervalMillis, UnaryOperator<String> prompt) {
        this.delegate = delegate;
        this.minIntervalMillis = minIntervalMillis;
        this.prompt = prompt;
    }

    @Override
    public LlmReply chat(String system, List<LlmMessage> conversation, List<ToolSpec> tools) {
        pace();
        String sent = prompt.apply(system);
        lastSystem = sent;
        long started = System.nanoTime();
        try {
            LlmReply reply = delegate.chat(sent, conversation, tools);
            calls.add(new Call(elapsed(started), reply.usage(), reply.toolCalls(), null));
            return reply;
        } catch (LlmException e) {
            calls.add(new Call(elapsed(started), LlmReply.Usage.UNKNOWN, List.of(), e.code()));
            throw e;
        }
    }

    /** 이번 질문의 기록을 꺼내고 비운다 */
    List<Call> drain() {
        List<Call> out = List.copyOf(calls);
        calls.clear();
        return out;
    }

    /** 마지막으로 보낸 지시문 (판 구분용 해시) */
    String lastSystem() {
        return lastSystem;
    }

    private void pace() {
        // 분당 한도 지키기. parkNanos는 일찍 깰 수 있어 정한 시각까지 다시 기다린다
        long until = lastStartedAt + minIntervalMillis;
        while (System.currentTimeMillis() < until && !Thread.currentThread().isInterrupted()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(until - System.currentTimeMillis()));
        }
        lastStartedAt = System.currentTimeMillis();
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
