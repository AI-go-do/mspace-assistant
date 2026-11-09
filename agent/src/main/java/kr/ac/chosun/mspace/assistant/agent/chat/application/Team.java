package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 학생이 이름 붙여 묶은 친구 모임 (FR-AGT-105 "팀 A랑 내일 3시", 11/9 회의).
 * 저장은 비서 쪽(학생별)이고 core는 바뀌지 않는다. 멤버 학번·가린 이름은 화면만 쓰고, 모델 지시문에는 팀 이름과 인원만 넣는다.
 *
 * @param name    팀 이름(1~12자). 예: "팀 A", "알고리즘 스터디"
 * @param members 대표자(나)를 뺀 친구들. 1~7명(5번 방 최대 8명 - 나)
 */
public record Team(String name, List<Member> members) {

    public static final int MAX_NAME = 12;
    public static final int MAX_MEMBERS = 7;
    public static final int MAX_TEAMS = 10;

    /** @param name core가 준 가린 이름(김*영). 화면에 다시 보여 주려고 함께 둔다 */
    public record Member(String studentId, String name) {
    }

    public Team {
        // null 원소도 그대로 두어 problem()이 400으로 잡게 한다(List.copyOf는 NPE → 500)
        members = members == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(members));
    }

    /** 나 포함 인원 */
    public int headcount() {
        return members.size() + 1;
    }

    /** 틀린 곳이 있으면 학생에게 보일 문장, 맞으면 empty */
    public Optional<String> problem() {
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME) {
            return Optional.of("팀 이름은 1~%d자로 적어 주세요.".formatted(MAX_NAME));
        }
        // 팀 이름은 외부 AI 지시문에 들어가니 학번·전화번호 같은 긴 숫자와 줄바꿈은 받지 않는다
        if (name.chars().anyMatch(Character::isISOControl) || name.matches(".*\\d{4,}.*")) {
            return Optional.of("팀 이름에는 학번·전화번호 같은 숫자나 줄바꿈을 넣을 수 없어요.");
        }
        if (members.isEmpty() || members.size() > MAX_MEMBERS) {
            return Optional.of("팀에는 친구를 1~%d명 넣을 수 있어요.".formatted(MAX_MEMBERS));
        }
        Set<String> seen = new HashSet<>();
        for (Member m : members) {
            if (m == null || m.studentId() == null || !m.studentId().matches("\\d{4,20}")) {
                return Optional.of("친구 학번이 올바르지 않아요.");
            }
            if (!seen.add(m.studentId())) {
                return Optional.of("같은 친구가 두 번 들어 있어요.");
            }
        }
        return Optional.empty();
    }

    /** 앞뒤 공백을 떼고, 가린 이름은 30자까지만 */
    public Team normalized() {
        return new Team(name.strip(), members.stream()
                .map(m -> new Member(m.studentId(), m.name() == null ? "" : m.name().strip().substring(0, Math.min(30, m.name().strip().length()))))
                .toList());
    }
}
