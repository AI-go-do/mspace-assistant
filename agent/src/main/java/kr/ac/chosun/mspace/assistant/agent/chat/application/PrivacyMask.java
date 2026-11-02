package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.regex.Pattern;

/**
 * 외부 AI로 보내기 전에 학번·전화번호를 가린다 (CLAUDE.md 4장). 학번은 원래 확인 화면에서 받지만(A-5),
 * 학생이 대화에 직접 적는 경우를 막는다. 이름은 규칙으로 못 가려서 남는다(한계, 트러블슈팅에 기록).
 */
public final class PrivacyMask {

    /** 조선대 학번 8자리(20으로 시작)로 추정. 다른 형식이 있으면 확인 필요 */
    private static final Pattern STUDENT_ID = Pattern.compile("(?<!\\d)20\\d{6}(?!\\d)");
    /** 010-1234-5678, 01012345678, +82 10-1234-5678, +82-(0)10-1234-5678 */
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+82[-\\s.]?(?:\\(0\\)[-\\s.]?)?1|01)[016789][-\\s.]?\\d{3,4}[-\\s.]?\\d{4}(?!\\d)");
    /** 일반 전화: 02-123-4567, 062-230-1234, 070-1234-5678, +82 62-230-1234, +82-(0)62-230-1234 (지역번호 02·031~064·070) */
    private static final Pattern LANDLINE = Pattern.compile(
            "(?<!\\d)(?:\\+82[-\\s.]?(?:\\(0\\)[-\\s.]?)?|0)(?:2|[3-6][1-5]|70)[-\\s.]?\\d{3,4}[-\\s.]?\\d{4}(?!\\d)");

    private PrivacyMask() {
    }

    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String masked = LANDLINE.matcher(PHONE.matcher(text).replaceAll("[전화번호]")).replaceAll("[전화번호]");
        return STUDENT_ID.matcher(masked).replaceAll("[학번]");
    }
}
