package kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web;

import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.INVALID_IMAGE;
import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.user;
import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.error;
import static kr.ac.chosun.mspace.assistant.agent.chat.adapter.in.web.WebRequests.unauthorized;

import kr.ac.chosun.mspace.assistant.agent.chat.application.AgentProperties;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ConfirmationGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ReadTimetableImage;
import kr.ac.chosun.mspace.assistant.agent.chat.application.ScheduleGateway;
import kr.ac.chosun.mspace.assistant.agent.chat.application.UserHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/**
 * 내 시간표 화면 (모델 없음, 캡처 읽기만 모델).
 * <ul>
 *   <li>GET·PUT·DELETE /api/agent/schedule: mcp-server /api/schedule/me로 그대로 넘김</li>
 *   <li>POST /api/agent/schedule/parse: 시간표 캡처(multipart image)를 모델이 읽어 classes 초안으로. 저장은 안 함 → 화면이 확인 카드 → PUT</li>
 * </ul>
 */
@RestController
class ScheduleController {

    private final ScheduleGateway schedules;
    private final ReadTimetableImage timetableImage;
    private final AgentProperties props;

    ScheduleController(ScheduleGateway schedules, ReadTimetableImage timetableImage, AgentProperties props) {
        this.schedules = schedules;
        this.timetableImage = timetableImage;
        this.props = props;
    }

    /** 내 시간표 보기. 없으면 mcp-server의 404 NO_SCHEDULE 그대로 */
    @GetMapping("/api/agent/schedule")
    ResponseEntity<Object> schedule(@RequestHeader HttpHeaders headers) {
        return relaySchedule("GET", headers, null);
    }

    /** 내 시간표 통째로 바꾸기. 본문 {classes:[{dayOfWeek, startTime, endTime, name}]} 검사는 mcp-server가 한다 */
    @PutMapping("/api/agent/schedule")
    ResponseEntity<Object> replaceSchedule(@RequestHeader HttpHeaders headers, @RequestBody(required = false) String body) {
        return relaySchedule("PUT", headers, body == null ? "{}" : body);
    }

    /**
     * 시간표 캡처 읽기. 그림은 모델 요청에만 실리고 저장·로그에 남지 않는다.
     * 응답 {classes:[…], dropped, note}. classes는 PUT /api/agent/schedule 본문과 같은 모양이라 확인 카드에서 고쳐 그대로 보낸다
     */
    @PostMapping(value = "/api/agent/schedule/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Object> parseSchedule(@RequestHeader HttpHeaders headers,
                                    @RequestPart(value = "image", required = false) MultipartFile image) {
        UserHeaders user = user(props, headers);
        if (user.isEmpty()) {
            return unauthorized();
        }
        if (image == null || image.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, INVALID_IMAGE, "시간표 사진을 올려 주세요.");
        }
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (java.io.IOException e) {
            return error(HttpStatus.BAD_REQUEST, INVALID_IMAGE, "사진을 읽지 못했어요. 다시 올려 주세요.");
        }
        ReadTimetableImage.Draft draft = timetableImage.read(image.getContentType(), bytes);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(draft);
    }

    @DeleteMapping("/api/agent/schedule")
    ResponseEntity<Object> deleteSchedule(@RequestHeader HttpHeaders headers) {
        return relaySchedule("DELETE", headers, null);
    }

    private ResponseEntity<Object> relaySchedule(String method, HttpHeaders headers, String body) {
        UserHeaders user = user(props, headers);
        if (user.isEmpty()) {
            return unauthorized();
        }
        ConfirmationGateway.Relayed relayed = schedules.relay(method, user, body);
        if (relayed.status() == HttpStatus.NO_CONTENT.value()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(relayed.status()).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(relayed.json());
    }
}
