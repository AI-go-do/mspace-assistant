package kr.ac.chosun.mspace.assistant.agent.eval;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ReadTimetableImage.ClassDraft;

/**
 * 시간표 사진 읽기 채점: <b>칸 단위</b>. 정답 칸마다 같은 요일·같은 이름(띄어쓰기 무시, 한쪽이 다른 쪽을 포함해도 인정)인 칸을 찾아
 * <ul>
 *   <li>정확: 시작·끝이 정답과 같음 (발표 숫자)</li>
 *   <li>30분 안: 시작·끝이 각각 30분 안으로 맞음 (얼마나 가까운지)</li>
 *   <li>빠짐: 짝이 없음 / 덤: 정답에 없는 칸을 지어냄</li>
 * </ul>
 * 점수 = 정확 칸 / 정답 칸 × 100. 덤 칸은 점수에서 빼지 않고 따로 센다(확인 카드에서 지우면 되니 빠짐보다 덜 나쁨).
 */
public final class TimetableScorer {

    public record Result(int answers, int exact, int within30, int missing, int extra, List<String> misses) {

        public double score() {
            return answers == 0 ? 0 : Math.round(exact * 1000.0 / answers) / 10.0;
        }
    }

    private TimetableScorer() {
    }

    public static Result score(List<ClassDraft> predicted, List<ClassDraft> answers) {
        List<ClassDraft> pool = new ArrayList<>(predicted);
        int exact = 0;
        int within30 = 0;
        int missing = 0;
        List<String> misses = new ArrayList<>();
        for (ClassDraft a : answers) {
            ClassDraft best = null;
            long bestGap = Long.MAX_VALUE;
            for (ClassDraft p : pool) {
                if (p.dayOfWeek().equals(a.dayOfWeek()) && sameName(p.name(), a.name())) {
                    long gap = gap(p, a);
                    if (gap < bestGap) {
                        best = p;
                        bestGap = gap;
                    }
                }
            }
            if (best == null) {
                missing++;
                misses.add(label(a) + " → 없음");
                continue;
            }
            pool.remove(best);
            if (bestGap == 0) {
                exact++;
                within30++;
            } else {
                if (minutes(best.startTime(), a.startTime()) <= 30 && minutes(best.endTime(), a.endTime()) <= 30) {
                    within30++;
                }
                misses.add(label(a) + " → " + best.startTime() + "~" + best.endTime());
            }
        }
        pool.forEach(p -> misses.add("덤 " + label(p)));
        return new Result(answers.size(), exact, within30, missing, pool.size(), misses);
    }

    static boolean sameName(String a, String b) {
        String x = a.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String y = b.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return !x.isEmpty() && !y.isEmpty() && (x.contains(y) || y.contains(x));
    }

    private static long gap(ClassDraft p, ClassDraft a) {
        return minutes(p.startTime(), a.startTime()) + minutes(p.endTime(), a.endTime());
    }

    private static long minutes(String a, String b) {
        return Math.abs(Duration.between(LocalTime.parse(a), LocalTime.parse(b)).toMinutes());
    }

    private static String label(ClassDraft c) {
        return c.dayOfWeek() + " " + c.startTime() + "~" + c.endTime() + " " + c.name();
    }
}
