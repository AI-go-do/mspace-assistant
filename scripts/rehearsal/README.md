# 시연 리허설 스크립트 (#16)

API 키·GPU 없이 **채팅 → agent → MCP 서버 → core** 전체 길을 한 번에 돌리고 결과를 표로 보여 줘요.
모델 자리에는 정해 둔 대로 도구를 부르는 가짜 Ollama(`fake_ollama.py`)를 둬요. 진짜 모델의 정확도는 #9에서 따로 재요.

## 무엇을 확인하나
| 시나리오 | 기대 |
|---|---|
| 내일 오후 2시 빈방 | `find_available_rooms` 1번 부르고 답 |
| 수업 끝나고 2시간 | 도구 없이 끝나는 시각을 되물음 |
| 예약 제안 | 확인 카드(요약·필요한 학번 수)만, 예약은 아직 안 생김 |
| 남이 확인 토큰 사용 / 같은 토큰 다시 | `CONFIRMATION_NOT_FOUND` / `CONFIRMATION_USED` |
| 본인이 [확인] (학번은 카드에서 입력) | core에 `BOOKED` |
| 입실 번호 (대표자 / 참여자) | 화면 카드만 / `NOT_RESERVATION_OWNER` |
| 내 예약 취소해 줘 → [확인] | `CANCELED` |
| 도구를 끝없이 부르는 모델 | 8번에서 멈춤 |
| 외부 AI로 나간 요청 전체 | 학번·확인 토큰·`accessCode` 0건 |

## 실행 (macOS 기준, Windows는 Git Bash)
```bash
# 0) 두 모듈 빌드 (target/은 저장소에 없어요. Windows는 mvnw.cmd)
./mvnw -q -DskipTests package
# 1) core: 임시 로그인(X-User-Id)을 켜고 띄우기 (core 레포 README 참고, 테스트 계정만)
# 2) 가짜 모델
SENT_LOG=/tmp/sent.jsonl python3 -I scripts/rehearsal/fake_ollama.py 18111 &
# 3) MCP 서버, agent
CORE_BASE_URL=http://localhost:8080 SERVER_PORT=8090 java -jar mcp-server/target/*.jar &
LLM_PROVIDER=ollama OLLAMA_BASE_URL=http://127.0.0.1:18111 MCP_SERVER_URL=http://localhost:8090 \
  SERVER_PORT=8091 java -jar agent/target/*.jar &
# 4) 시나리오 (스크립트가 MCP 서버와 agent의 /actuator/health를 각각 60초까지 기다리고, 안 뜨면 멈춰요)
MCP=http://localhost:8090 AGENT=http://localhost:8091 OWNER=4 OTHER=2 P1=29990002 P2=29990003 SENT_LOG=/tmp/sent.jsonl \
  scripts/rehearsal/scenarios.sh
```
- `OWNER`는 끝나지 않은 예약이 없는 테스트 계정이어야 해요 (대표자 동시 1건 규칙)
- 실제 학생 정보는 쓰지 않아요. 시연은 테스트 계정·가짜 데이터로만 해요
- 시작할 때 `SENT_LOG`를 비워서 이번 실행의 요청만 검사해요. 로그를 못 읽거나 요청이 0건이면 실패로 쳐요
- 실패하면 표의 "실제" 칸에 받은 응답이 그대로 나와요

## 한계
- 가짜 모델은 낱말로 도구를 고르므로, 모델이 인자를 맞게 넣는지는 확인하지 않아요 (#9)
- 11/3 로컬 실행 결과: 11개 모두 통과 (core develop `15c6ce3`, mcp-server #40, agent #41)
