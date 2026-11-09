# mspace-assistant

"내일 수업 끝나고 빈방 잡아 줘"를 실제 예약으로 바꾸는 M-SPACE 예약 시스템의 AI 비서예요.

- **MCP 서버:** 빈방 찾기, 예약·취소·반납, 내 예약, 방 현황, 내 시간표(학생이 직접 넣거나 캡처를 Gemini가 읽어 초안으로, #63)를 도구로 열어요(나중에 학과 공지, 휴강·보강)
- **에이전트:** 도구 결과를 모델에 돌려주고 다시 묻는 반복으로 여러 도구를 이어 불러요
- **안전장치:** AI 모델을 믿지 않아요. 권한은 예약 코어가 검사하고, 예약·취소 같은 쓰기 작업은 사용자가 확인한 뒤에만 실행해요
- **모델 교체:** 어댑터 구조로 Gemini와 로컬 Ollama를 바꿔 끼워요 (`LLM_PROVIDER=gemini|ollama`)

## 문서
- [팀 공통 맥락](TEAM.md)
- [작업 가이드 (Claude Code용)](CLAUDE.md)
- [일정](docs/일정.md)
- [도구 명세](docs/도구-명세.md) · [설계](docs/설계.md)
- [회의 기록](docs/meetings/README.md)
- [시연 리허설(가짜 모델)](scripts/rehearsal/README.md) · [성공률 측정 #9(진짜 모델)](scripts/measure/README.md) · [모델·지시문 평가(점수 기준)](scripts/eval/README.md)

## 상태
M2 구현 중이에요 (11/7–11/13, 11/13 기능 동결). Java 21 · Spring Boot 4 · Maven으로 만들어요.

| 모듈 | 포트 | 열어 둔 것 |
|---|---|---|
| `mcp-server` | 8090 | MCP `/mcp` (읽기 6 `find_available_rooms`·`room_status`·`my_reservations`·`usage_rules`·`my_schedule`·`week_plan`, 쓰기 5 `create`·`start_now`·`cancel`·`checkout`·`continue`), 확인 실행 `POST /api/confirmations/{token}` |
| `agent` | 8091 | 채팅 접수 `POST /api/agent/chat`(202 + jobId) → 결과 조회 `GET /api/agent/chat/{jobId}`(폴링), 새 대화 `DELETE /api/agent/chat/history`, 확인 `POST /api/agent/confirmations/{token}`(결과를 대화 맥락에 남기고 다음 한 수 `suggestions`를 붙임), 입실 번호 카드 `GET /api/agent/reservations/{id}/access-code`, 내 시간표 `GET·PUT·DELETE /api/agent/schedule`, 시간표 캡처 읽기 `POST /api/agent/schedule/parse`(초안만, 저장은 PUT), 비서 성향 `GET·PUT /api/agent/persona`(조용히·알아서·잔소리, 채팅 "잔소리 모드로"도 됨), 오늘 브리핑 `GET /api/agent/briefing`(시간표·예약·빈 방·수업 뒤 칸·공강 칸 한 문단 + 한 번에 잡기 버튼 문장, 모델 안 거침) |

## 로컬 실행
```bash
mvn -B verify                                   # 전체 테스트 (core·Gemini는 가짜 서버)
# 아래 두 서버는 셸을 차지하니 터미널을 따로 열어 하나씩 띄우고, curl은 세 번째 터미널에서 실행해요
# local 프로필: 로그인 전 임시 헤더 X-User-Id를 넘긴다 (core도 MSPACE_DEV_HEADER=true로 띄워요)
SPRING_PROFILES_ACTIVE=local CORE_BASE_URL=http://localhost:8080 mvn -pl mcp-server spring-boot:run  # core가 다른 서버면 https + CORE_REQUIRE_HTTPS=true (#44)
SPRING_PROFILES_ACTIVE=local GEMINI_API_KEY=... mvn -pl agent spring-boot:run  # 키는 .env에만. 커밋 금지
curl -X POST localhost:8091/api/agent/chat -H 'X-User-Id: 1' -H 'Content-Type: application/json' \
     -d '{"message":"내일 오후 2시 빈방 있어?"}'     # → 202 {"jobId":"j_…","status":"PENDING","pollAfterMs":1000}
curl localhost:8091/api/agent/chat/j_… -H 'X-User-Id: 1'  # PENDING이면 1초 뒤 다시. DONE이면 result에 답·확인 카드·tools·hints(OPEN_SCHEDULE: [내 시간표] 버튼)·suggestions(답 아래 빠른 답장 버튼 글)
```
- 채팅은 **접수 → 결과 조회(폴링)** 방식이에요 (5차 회의). 요청 하나가 바로 끝나서 nginx 제한 시간(60초)에 걸리지 않아요. 결과는 접수한 학생만 볼 수 있고(남의 jobId는 404), 끝난 뒤 5분 보관해요. 학생 한 명은 동시에 한 건만(`409 CHAT_IN_PROGRESS`), 서버가 바쁘면 `503 AGENT_BUSY`. 메모리 보관이라 agent는 한 대 기준이에요
- 사용자 인증은 기본으로 `Authorization`만 넘겨요. 로그인 전 임시 `X-User-Id` 헤더는 `local` 프로필(`application-local.yml`)에서만 켜요. 서버 배포에서는 켜지 않아요 (헤더 한 줄로 남의 예약에 닿을 수 있어서)
