package kr.ac.chosun.mspace.assistant.mcp.schedule.adapter.in.web;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.adapter.in.web.ForwardedCredentials;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.ManageSchedule;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.ManageSchedule.ClassInput;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.Timetable;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.WeeklyClass;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 시간표 API. MCP 도구가 아니라 일반 REST라서 모델은 넣거나 지울 수 없다.
 * 학생 화면이 agent(/api/agent/schedule)를 거쳐 학생 본인 헤더로 부른다.
 * <ul>
 *   <li>PUT /api/schedule/me {classes:[{dayOfWeek:"MON", startTime:"09:00", endTime:"10:15", name:"자료구조"}]} → 200 시간표</li>
 *   <li>GET /api/schedule/me → 200 시간표, 없으면 404 NO_SCHEDULE</li>
 *   <li>DELETE /api/schedule/me → 204</li>
 * </ul>
 */
@RestController
class ScheduleController {

    private final ManageSchedule schedule;
    private final ForwardedCredentials credentials;

    ScheduleController(ManageSchedule schedule, ForwardedCredentials credentials) {
        this.schedule = schedule;
        this.credentials = credentials;
    }

    @PutMapping("/api/schedule/me")
    ResponseEntity<TimetableResponse> replace(@RequestHeader HttpHeaders headers, @RequestBody(required = false) ScheduleRequest body) {
        Timetable saved = schedule.replace(credentials.require(headers), body == null ? null : body.classes());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(TimetableResponse.of(saved));
    }

    @GetMapping("/api/schedule/me")
    ResponseEntity<TimetableResponse> find(@RequestHeader HttpHeaders headers) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(TimetableResponse.of(schedule.find(credentials.require(headers))));
    }

    @DeleteMapping("/api/schedule/me")
    ResponseEntity<Void> delete(@RequestHeader HttpHeaders headers) {
        schedule.delete(credentials.require(headers));
        return ResponseEntity.noContent().build();
    }

    record ScheduleRequest(List<ClassInput> classes) {
    }

    /**
     * 응답은 PUT 본문과 같은 모양으로 낸다(요일 MON, 시각 HH:mm). 화면은 받은 시간표를 고쳐 그대로 다시 넣는다.
     * 도메인 값(DayOfWeek·LocalTime)을 그대로 내보내면 MONDAY·10:30:00이 되어 화면이 요일을 못 알아본다 (트러블슈팅 TS-15)
     */
    record TimetableResponse(List<ClassResponse> classes, OffsetDateTime updatedAt) {

        static TimetableResponse of(Timetable t) {
            return new TimetableResponse(t.classes().stream().map(ClassResponse::of).toList(), t.updatedAt());
        }
    }

    record ClassResponse(String dayOfWeek, String startTime, String endTime, String name) {

        private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

        static ClassResponse of(WeeklyClass c) {
            return new ClassResponse(c.dayOfWeek().name().substring(0, 3), c.startTime().format(HHMM),
                    c.endTime().format(HHMM), c.name());
        }
    }
}
