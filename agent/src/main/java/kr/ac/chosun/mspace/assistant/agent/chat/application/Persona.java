package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 비서 성향 (11/6 8차 멘토 점검 권장: "수동/능동/잔소리를 학생이 고른다").
 * <p>
 * 학생이 화면({@code PUT /api/agent/persona}) 또는 채팅("잔소리 모드로 해 줘")으로 고르면 서버가 기억하고,
 * 모델 지시문의 말투·제안 규칙만 바뀐다. 안전 규칙(두 단계 확인·개인정보)은 성향과 상관없이 같다.
 * <ul>
 *   <li>{@link #QUIET} 조용히: 물은 것만 짧게. 제안·권유 없음</li>
 *   <li>{@link #ACTIVE} 알아서(기본): 지금까지의 비서. 답 끝에 다음 행동 하나 제안</li>
 *   <li>{@link #NAGGING} 잔소리: 입실·퇴실·연장·노쇼·시간표를 매번 챙기고 재촉</li>
 * </ul>
 */
public enum Persona {

    QUIET("조용히", "물은 것만 짧게 답해요. 먼저 제안하지 않아요.",
            """
            성향: 조용히. 학생이 물은 것만 한두 문장으로 답한다. 묻지 않은 제안·권유·잔소리는 하지 않는다.
            다음 행동을 제안하라는 다른 규칙보다 이 규칙이 우선한다(도구 결과의 note·message는 그대로 전한다)."""),
    ACTIVE("알아서", "답 끝에 지금 할 수 있는 다음 행동을 하나 제안해요. (기본)",
            "성향: 알아서. 답 끝에 지금 할 수 있는 다음 행동을 하나 제안한다(예: \"바로 잡을까요?\", \"연장할까요?\")."),
    NAGGING("잔소리", "입실·퇴실·연장·노쇼·시간표를 매번 챙기고 재촉해요.",
            """
            성향: 잔소리. 답마다 끝에 한 줄로 챙긴다: 예약이 있으면 입실(미리 예약은 시작 15분, 즉시 시작은 10분 안에 입실 안 하면
            노쇼)·퇴실 버튼·연장은 끝나기 10분 전부터만 된다는 것을 짚는다. 시간표가 없으면(NO_SCHEDULE) 넣으라고 한다.
            빈방을 안내할 때는 "지금 안 잡으면 다른 사람이 잡아요"처럼 재촉한다. 말투는 친한 선배처럼 하되 반말은 쓰지 않는다.""");

    public static final Persona DEFAULT = ACTIVE;

    /** 채팅으로 바꾸는 말: "잔소리 모드로", "조용한 비서로 해 줘", "알아서 하는 성향으로 바꿔" */
    private static final Pattern REQUEST = Pattern.compile(
            "(조용|수동|능동|알아서|잔소리).{0,6}?(모드|성향|비서|스타일)", Pattern.CANON_EQ);

    /** 요청 뒤의 부정: 바꾸지 마, 하지 말고, 싫어, 안 해, 바꾸면 안 돼, 그만 */
    private static final Pattern NEGATION = Pattern.compile("지\\s*마|말아|말고|싫|안\\s*해|안\\s*할|면\\s*안|그만|아니", Pattern.CANON_EQ);

    private final String label;
    private final String description;
    private final String promptRule;

    Persona(String label, String description, String promptRule) {
        this.label = label;
        this.description = description;
        this.promptRule = promptRule;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** 시스템 지시문에 붙는 규칙 한 토막 */
    public String promptRule() {
        return promptRule;
    }

    /** 저장·API 값 ("QUIET"). 모르는 값·null이면 빈 Optional */
    public static Optional<Persona> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 채팅 말에서 성향 바꾸기 요청을 찾는다. 모델 없이 바로 바꾼다 */
    public static Optional<Persona> requested(String message) {
        if (message == null) {
            return Optional.empty();
        }
        Matcher m = REQUEST.matcher(message);
        if (!m.find() || NEGATION.matcher(message.substring(m.end())).find()) {
            return Optional.empty(); // "잔소리 모드로 바꾸지 마"는 바꾸라는 말이 아니다 → 모델에 넘긴다
        }
        return Optional.of(switch (m.group(1)) {
            case "조용", "수동" -> QUIET;
            case "잔소리" -> NAGGING;
            default -> ACTIVE;
        });
    }

    public static List<Persona> all() {
        return List.of(values());
    }
}
