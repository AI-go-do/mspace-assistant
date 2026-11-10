package kr.ac.chosun.mspace.assistant.agent.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결과 남기기. 한 번 실행에 Markdown 보고서 1개 + 모델·지시문 판마다 history.jsonl 한 줄 + 판 전체 순위표(scoreboard.md).
 * history.jsonl은 지우지 않고 쌓아서 "기준(지시문 v1)에서 몇 점 → 몇 점"을 그린다. 학생 말·토큰은 없다(가짜 질문·가짜 데이터).
 *
 * <p>표 모양은 멘토가 보여 준 본보기(11/10)를 따른다: 순위 · 방식 · 판단(기준/채택/비교/탈락) · 점수(회차 평균, 아래에 회차별 값) ·
 * 심각 오류 · 시간(평균, 최대) · 자원 · 비용. 표 아래에 판마다 "무엇을 바꿨고 왜 점수가 변했나" 한 단락.
 */
final class ReportWriter {

    static final String SETUP = "같은 발화 세트를 같은 가짜 도구·고정 시각(2026-11-10 화 10:25)에서 돌리고, 회차마다 채점해 평균을 냈어요. "
            + "점수는 질문마다 도구 50 · 인자 30 · 답 20이고, **대화로 예약이 끝까지 되는 흐름**(예약·즉시 시작·연장·취소·퇴실·대화형·지난번처럼·시간표)은 2배로 세요. "
            + "**심각 오류**는 [확인]만 누르면 잘못된 일이 실제로 일어나는 실수예요: 틀린 방·시각·예약·분으로 쓰기 도구를 부름, 부르면 안 되는 쓰기 도구, "
            + "확인 전에 끝났다고 말함, 6자리 숫자. 한 판에는 한 가지만 바꿔요.";

    private ReportWriter() {
    }

    static Path write(Path dir, String stamp, List<EvalRunner.Report> reports, JsonMapper json) {
        try {
            Files.createDirectories(dir);
            Path history = dir.resolve("history.jsonl");
            if (Files.exists(history)) {
                readHistory(history, json); // 깨진 줄이 있으면 새 기록을 붙이기 전에 멈춘다(실패한 판이 쌓이지 않게)
            }
            for (EvalRunner.Report r : reports) {
                Files.writeString(history, json.writeValueAsString(r.summary()) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Path detail = dir.resolve(stamp + "-detail.json");
            Files.writeString(detail, json.writerWithDefaultPrettyPrinter().writeValueAsString(details(reports)),
                    StandardCharsets.UTF_8);
            Path md = dir.resolve(stamp + ".md");
            Files.writeString(md, markdown(stamp, reports), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("scoreboard.md"), scoreboard(readHistory(history, json)), StandardCharsets.UTF_8);
            return md;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> readHistory(Path history, JsonMapper json) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(history, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                rows.add(json.readValue(line, Map.class));
            }
        }
        return rows;
    }

    /** 판 전체 순위표. history.jsonl의 모든 줄을 점수 순으로. 판단을 바꾸려면 history.jsonl의 verdict를 고치고 아무 실행이나 다시 하면 된다 */
    static String scoreboard(List<Map<String, Object>> rows) {
        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing((Map<String, Object> r) -> r.get("score") == null ? -1.0
                : ((Number) r.get("score")).doubleValue()).reversed());
        StringBuilder b = new StringBuilder("# 비서 모델·지시문 순위표\n\n").append(SETUP).append("\n\n");
        table(b, sorted, true);
        notes(b, sorted);
        return b.toString();
    }

    static String markdown(String stamp, List<EvalRunner.Report> reports) {
        StringBuilder b = new StringBuilder("# 모델 평가 ").append(stamp).append(" (+09:00)\n\n").append(SETUP).append("\n\n");
        List<Map<String, Object>> rows = reports.stream().map(EvalRunner.Report::summary).toList();
        table(b, rows, false);
        notes(b, rows);

        b.append("\n## 세부 지표\n\n");
        b.append("| 방식 | 예약 흐름 완주 | 단순 평균 | 완전 통과 | 도구 | 인자 | 답 | 실패율 | 흔들림 | 세트별 |\n");
        b.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map<String, Object> s : rows) {
            b.append("| ").append(method(s)).append(" | **").append(s.get("flowCompletion")).append("%** | ")
                    .append(s.get("plainScore")).append(" | ").append(s.get("passRate")).append("% | ")
                    .append(s.get("toolAccuracy")).append("% | ").append(s.get("argAccuracy")).append("% | ")
                    .append(s.get("replyAccuracy")).append("% | ").append(s.get("failureRate")).append("% | ")
                    .append(s.get("unstableCases")).append(" | ").append(s.get("bySet")).append(" |\n");
        }

        for (EvalRunner.Report r : reports) {
            List<String> severe = r.runs().stream().filter(x -> !x.score().severe().isEmpty())
                    .map(x -> x.run() + "회 " + x.evalCase().id() + ": " + String.join("; ", x.score().severe())).toList();
            if (!severe.isEmpty()) {
                b.append("\n## ").append(method(r.summary())).append(" — 심각 오류\n\n");
                severe.forEach(x -> b.append("- ").append(x.replace("|", "/")).append("\n"));
            }
            b.append("\n## ").append(method(r.summary())).append(" — 질문별\n\n");
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
                        .append(c.flow() ? " ×2" : "").append(" | ").append(pass).append("/").append(xs.size()).append(" | ")
                        .append(Math.round(avg)).append(" | ").append(misses.replace("|", "/")).append(" |\n");
            });
        }
        return b.toString();
    }

    /** 본보기 표. 점수가 없는 판(옛 기록·실행 실패)은 순위 대신 – */
    private static void table(StringBuilder b, List<Map<String, Object>> rows, boolean ranked) {
        b.append("| 순위 | 방식 | 판단 | 점수 | 심각 오류 | 시간 | 자원 | 비용 |\n|---|---|---|---|---|---|---|---|\n");
        int rank = 0;
        for (Map<String, Object> s : rows) {
            boolean scored = s.get("scoreRuns") != null;
            String place = ranked && scored ? String.valueOf(++rank) : ranked ? "–" : "";
            b.append("| ").append(place).append(" | ").append(method(s)).append("<br><sub>").append(s.get("chats"))
                    .append("대화 · ").append(s.get("runs")).append("회 · `").append(s.get("promptHash")).append("`</sub> | ")
                    .append(s.getOrDefault("verdict", "–")).append(" | ")
                    .append(scored ? "**" + s.get("score") + "**<br><sub>" + joinRuns(s.get("scoreRuns")) + "</sub>" : "–")
                    .append(" | ")
                    .append(s.get("severeErrors") == null ? "–" : s.get("severeErrors") + "<br><sub>" + joinRuns(s.get("severeRuns")) + "</sub>")
                    .append(" | ").append(time(s)).append(" | ").append(resource(s)).append(" | ").append(cost(s)).append(" |\n");
        }
    }

    private static void notes(StringBuilder b, List<Map<String, Object>> rows) {
        List<Map<String, Object>> withNote = rows.stream()
                .filter(s -> s.get("note") != null && !String.valueOf(s.get("note")).isBlank()).toList();
        if (withNote.isEmpty()) {
            return;
        }
        b.append("\n");
        for (Map<String, Object> s : withNote) {
            b.append("**").append(method(s)).append("** (").append(s.get("runs")).append("회").append(when(s)).append("): ").append(s.get("note")).append(" 점수 ").append(s.get("score"))
                    .append(", 심각 오류 ").append(s.get("severeErrors")).append(", 예약 흐름 완주 ")
                    .append(s.getOrDefault("flowCompletion", "–")).append("%.\n\n");
        }
    }

    /** 같은 방식이 여러 판일 때 구분하려고 실행 시각(MM-DD HH:mm)을 붙인다 */
    private static String when(Map<String, Object> s) {
        String at = String.valueOf(s.getOrDefault("at", ""));
        return at.length() >= 16 ? ", " + at.substring(5, 16).replace('T', ' ') : "";
    }

    static String method(Map<String, Object> s) {
        return s.get("model") + " · 지시문 " + s.get("prompt");
    }

    @SuppressWarnings("unchecked")
    private static String joinRuns(Object runs) {
        return runs == null ? "" : String.join(" / ", ((List<Object>) runs).stream().map(String::valueOf).toList());
    }

    private static String time(Map<String, Object> s) {
        Object avg = s.get("latencyAvgMs");
        if (avg == null) {
            return "–";
        }
        return seconds(avg) + "<br><sub>최대 " + seconds(s.get("latencyMaxMs")) + "</sub>";
    }

    private static String resource(Map<String, Object> s) {
        String calls = "호출 " + s.get("modelCallsPerChat") + "회/대화";
        Object in = s.get("inputTokensPerChat");
        return in == null ? calls : calls + "<br><sub>토큰 " + in + " / " + s.get("outputTokensPerChat") + "</sub>";
    }

    private static String cost(Map<String, Object> s) {
        Object free = s.get("freeChatsPerDay");
        if (free == null) {
            Object rpd = s.get("freeRequestsPerDay");
            return rpd == null ? "확인 필요" : "0원<br><sub>무료 " + rpd + "회/일, 대화 수 확인 필요</sub>";
        }
        return ((Number) free).longValue() < 0 ? "0원<br><sub>로컬(젯슨 보드 A)</sub>" : "0원<br><sub>무료 " + free + "대화/일</sub>";
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
                d.put("set", x.evalCase().set());
                d.put("points", Math.round(x.score().points()));
                d.put("pass", x.score().pass());
                d.put("severe", x.score().severe());
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
}
