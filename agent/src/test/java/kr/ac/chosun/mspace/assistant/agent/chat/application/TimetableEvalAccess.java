package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.util.List;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ReadTimetableImage.ClassDraft;
import kr.ac.chosun.mspace.assistant.agent.llm.application.ImageReader;
import tools.jackson.databind.json.JsonMapper;

/** 평가(eval 패키지)가 ReadTimetableImage의 package-private 해석기를 쓰게 여는 테스트 전용 창구 */
public final class TimetableEvalAccess {

    private final ReadTimetableImage service;

    public TimetableEvalAccess(JsonMapper json) {
        this.service = new ReadTimetableImage(ImageReader.UNSUPPORTED, json);
    }

    public String instruction() {
        return ReadTimetableImage.INSTRUCTION;
    }

    public List<ClassDraft> modelTimes(String modelText) {
        return service.parse(modelText).classes();
    }

    public List<ClassDraft> measured(String modelText, byte[] image) {
        return service.parse(modelText, image).classes();
    }
}
