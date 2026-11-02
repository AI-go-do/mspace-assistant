# mspace-assistant

"내일 수업 끝나고 빈방 잡아 줘"를 실제 예약으로 바꾸는 M-SPACE 예약 시스템의 AI 비서예요.

- **MCP 서버:** 빈방 찾기, 예약·취소·반납, 내 예약, 방 현황(나중에 학과 공지, 휴강·보강, 내 시간표)을 도구로 열어요
- **에이전트:** 도구 결과를 모델에 돌려주고 다시 묻는 반복으로 여러 도구를 이어 불러요
- **안전장치:** AI 모델을 믿지 않아요. 권한은 예약 코어가 검사하고, 예약·취소 같은 쓰기 작업은 사용자가 확인한 뒤에만 실행해요
- **모델 교체:** 어댑터 구조로 Gemini와 로컬 Ollama를 바꿔 끼워요 (`LLM_PROVIDER=gemini|ollama`)

## 문서
- [팀 공통 맥락](TEAM.md)
- [작업 가이드 (Claude Code용)](CLAUDE.md)
- [일정](docs/일정.md)
- [도구 명세](docs/도구-명세.md) · [설계](docs/설계.md)
- [회의 기록](docs/meetings/README.md)

## 상태
M2 구현 중이에요 (11/7–11/13, 11/13 기능 동결). Java 21 · Spring Boot 4 · Maven으로 만들어요.

| 모듈 | 포트 | 열어 둔 것 |
|---|---|---|
| `mcp-server` | 8090 | MCP `/mcp` (도구 7개 중 읽기 2·쓰기 3 구현), 확인 실행 `POST /api/confirmations/{token}` |
| `agent` | 8091 | 채팅 `POST /api/agent/chat`, 확인 `POST /api/agent/confirmations/{token}` |

## 로컬 실행
```bash
mvn -B verify                                   # 전체 테스트 (core·Gemini는 가짜 서버)
CORE_BASE_URL=http://localhost:8080 mvn -pl mcp-server spring-boot:run
GEMINI_API_KEY=... mvn -pl agent spring-boot:run  # 키는 .env에만. 커밋 금지
curl -X POST localhost:8091/api/agent/chat -H 'X-User-Id: 1' -H 'Content-Type: application/json' \
     -d '{"message":"내일 오후 2시 빈방 있어?"}'
```
- 사용자 인증은 core와 같이 임시 `X-User-Id` 헤더예요. JWT로 바뀌면 `mspace.agent.forward-headers`만 고쳐요
