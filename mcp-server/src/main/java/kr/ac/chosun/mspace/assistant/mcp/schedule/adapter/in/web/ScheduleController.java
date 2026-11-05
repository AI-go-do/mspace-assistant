package kr.ac.chosun.mspace.assistant.mcp.schedule.adapter.in.web;

import java.util.List;
import kr.ac.chosun.mspace.assistant.mcp.common.adapter.in.web.ForwardedCredentials;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.ManageSchedule;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.ManageSchedule.ClassInput;
import kr.ac.chosun.mspace.assistant.mcp.schedule.application.Timetable;
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
    ResponseEntity<Timetable> replace(@RequestHeader HttpHeaders headers, @RequestBody(required = false) ScheduleRequest body) {
        Timetable saved = schedule.replace(credentials.require(headers), body == null ? null : body.classes());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(saved);
    }

    @GetMapping("/api/schedule/me")
    ResponseEntity<Timetable> find(@RequestHeader HttpHeaders headers) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(schedule.find(credentials.require(headers)));
    }

    @DeleteMapping("/api/schedule/me")
    ResponseEntity<Void> delete(@RequestHeader HttpHeaders headers) {
        schedule.delete(credentials.require(headers));
        return ResponseEntity.noContent().build();
    }

    record ScheduleRequest(List<ClassInput> classes) {
    }
}
