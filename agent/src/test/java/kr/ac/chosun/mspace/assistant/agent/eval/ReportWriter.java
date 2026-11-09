package kr.ac.chosun.mspace.assistant.agent.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결과 남기기. 한 번 실행에 Markdown 보고서 1개 + 모델·지시문 판마다 history.jsonl 한 줄.
 * history.jsonl은 지우지 않고 쌓아서 "지시문 v1 → v3에서 몇 점 → 몇 점"을 그린다. 학생 말·토큰은 없다(가짜 질문·가짜 데이터).
 */
final class ReportWriter {

    private ReportWriter() {
    }

    static Path write(Path dir, String stamp, List<EvalRunner.Report> reports, JsonMapper json) {
        try {
            Files.createDirectories(dir);
            Path history = dir.resolve("history.jsonl");
            for (EvalRunner.Report r : reports) {
                Files.writeString(history, json.writeValueAsString(r.summary()) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Path detail = dir.resolve(stamp + "-detail.json");
            Files.writeString(detail, json.writerWithDefaultPrettyPrinter().writeValueAsString(details(reports)),
                    StandardCharsets.UTF_8);
            Path md = dir.resolve(stamp + ".md");
            Files.writeString(md, markdown(stamp, reports), StandardCharsets.UTF_8);
            return md;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String markdown(String stamp, List<EvalRunner.Report> reports) {
        StringBuilder b = new StringBuilder("# 모델 평가 ").append(stamp).append(" (+09:00)\n\n");
        b.append("가짜 도구·고정 시각(2026-11-10 화 10:00)에서 같은 발화 세트를 돌린 결과예요. 채점: 도구 50% · 인자 30% · 답 20%.\n\n");
        b.append("| 모델 | 지시문 | 종합 점수 | 완전 통과 | 도구 | 인자 | 답 | 안전 위반 | 실패율 | 응답 중앙값 | p90 | 호출/대화 | 토큰 입력/출력 | 무료 대화/일 |\n");
        b.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (EvalRunner.Report r : reports) {
            Map<String, Object> s = r.summary();
            b.append("| ").append(s.get("model")).append(" | ").append(s.get("prompt")).append(" `").append(s.get("promptHash"))
                    .append("` | **").append(s.get("score")).append("** | ").append(s.get("passRate")).append("% | ")
                    .append(s.get("toolAccuracy")).append("% | ").append(s.get("argAccuracy")).append("% | ")
                    .append(s.get("replyAccuracy")).append("% | ").append(s.get("safetyViolations")).append(" | ")
                    .append(s.get("failureRate")).append("% | ").append(seconds(s.get("latencyMedianMs"))).append(" | ")
                    .append(seconds(s.get("latencyP90Ms"))).append(" | ").append(s.get("modelCallsPerChat")).append(" | ")
                    .append(s.get("inputTokensPerChat")).append(" / ").append(s.get("outputTokensPerChat")).append(" | ")
                    .append(freeChats(s.get("freeChatsPerDay"))).append(" |\n");
        }
        for (EvalRunner.Report r : reports) {
            b.append("\n## ").append(r.summary().get("model")).append(" — 질문별\n\n");
            b.append("| 질문 | 묶음 | 통과/시도 | 평균 점수 | 놓친 것 (회차별, 같으면 한 번) |\n|---|---|---|---|---|\n");
            Map<String, List<EvalRunner.CaseRun>> byCase = new LinkedHashMap<>();
            r.runs().forEach(x -> byCase.computeIfAbsent(x.evalCase().id(), k -> new ArrayList<>()).add(x));
            byCase.forEach((id, xs) -> {
                EvalCase c = xs.getFirst().evalCase();
                long pass = xs.stream().filter(x -> x.score().pass()).count();
                double avg = xs.stream().mapToDouble(x -> x.score().points()).average().orElse(0);
                String misses = String.join(" // ", xs.stream().filter(x -> !x.score().pass())
                        .map(x -> String.join("; ", x.score().misses())).distinct().toList());
                b.append("| ").append(id).append(" ").append(c.text().replace("|", "/")).append(" | ").append(c.group())
                        .append(" | ").append(pass).append("/").append(xs.size()).append(" | ")
                        .append(Math.round(avg)).append(" | ").append(misses.replace("|", "/")).append(" |\n");
            });
        }
        return b.toString();
    }

    private static List<Map<String, Object>> details(List<EvalRunner.Report> reports) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EvalRunner.Report r : reports) {
            for (EvalRunner.CaseRun x : r.runs()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("model", r.summary().get("model"));
                d.put("prompt", r.summary().get("prompt"));
                d.put("run", x.run());
                d.put("id", x.evalCase().id());
                d.put("points", Math.round(x.score().points()));
                d.put("pass", x.score().pass());
                d.put("tools", x.tools());
                d.put("calls", x.calls().stream().map(k -> Map.of("ms", k.millis(),
                        "toolCalls", k.toolCalls().stream().map(t -> Map.of("name", t.name(), "args", t.arguments())).toList(),
                        "error", k.errorCode() == null ? "" : k.errorCode())).toList());
                d.put("misses", x.score().misses());
                d.put("reply", x.snippet());
                out.add(d);
            }
        }
        return out;
    }

    private static String seconds(Object millis) {
        return millis == null ? "-" : String.format("%.1f초", ((Number) millis).longValue() / 1000.0);
    }

    private static String freeChats(Object v) {
        if (v == null) {
            return "확인 필요";
        }
        return ((Number) v).longValue() < 0 ? "제한 없음(로컬)" : v.toString();
    }
}
