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

## 준비물
| 무엇 | 조건 |
|---|---|
| Java 21, Maven | 두 모듈 빌드 |
| python3 (3.9 이상, 표준 라이브러리만) | 가짜 모델·결과 판정 (`zoneinfo`를 써요) |
| core | `MSPACE_DEV_HEADER=true`로 띄워 임시 로그인 헤더 `X-User-Id`를 켜요. 테스트 계정·가짜 데이터만 |
| mcp-server, agent | `SPRING_PROFILES_ACTIVE=local`. 기본 설정은 `X-User-Id`를 넘기지 않아요 (서버 배포용) |
| `OWNER` | 테스트 계정. 끝나지 않은 예약이 있어도 스크립트가 시작·끝에 core에서 직접 정리해요(취소·퇴실) |
| `OTHER`, `P1` | `P1`은 `OTHER` 계정의 학번이어야 해요. 그래야 "입실 번호(참여자)"에서 OTHER가 그 예약의 참여자가 돼요 |
| `P2` | 다른 테스트 학번 |

## 실행 (macOS 기준, Windows는 Git Bash)
```bash
# 0) 두 모듈 빌드 (target/은 저장소에 없어요. Windows는 mvnw.cmd)
./mvnw -q -DskipTests package
# 1) core: 임시 로그인(X-User-Id)을 켜고 띄우기 (core 레포 README 참고, 테스트 계정만)
# 2) 가짜 모델
SENT_LOG=/tmp/sent.jsonl python3 -I scripts/rehearsal/fake_ollama.py 18111 &
# 3) MCP 서버, agent
SPRING_PROFILES_ACTIVE=local CORE_BASE_URL=http://localhost:8080 SERVER_PORT=8090 java -jar mcp-server/target/*.jar &
SPRING_PROFILES_ACTIVE=local LLM_PROVIDER=ollama OLLAMA_BASE_URL=http://127.0.0.1:18111 MCP_SERVER_URL=http://localhost:8090 \
  SERVER_PORT=8091 java -jar agent/target/*.jar &
# 4) 시나리오 (스크립트가 MCP 서버와 agent의 /actuator/health를 각각 60초까지 기다리고, 안 뜨면 멈춰요)
CORE=http://localhost:8080 MCP=http://localhost:8090 AGENT=http://localhost:8091 OWNER=4 OTHER=2 P1=29990002 P2=29990003 SENT_LOG=/tmp/sent.jsonl \
  scripts/rehearsal/scenarios.sh
```
- 앞 실행이 중간에 멈춰 `OWNER`에게 예약이 남아도, 시작할 때와 끝날 때(실패해도 `trap`) 정리해서 대표자 동시 1건 규칙에 걸리지 않아요. 그래서 **테스트 계정에만** 써요
- 가짜 모델은 "내일"을 요청마다 서울 날짜로 계산해요. 자정을 넘기거나 UTC 기기에서 돌려도 core와 하루 어긋나지 않아요
- 실제 학생 정보는 쓰지 않아요. 시연은 테스트 계정·가짜 데이터로만 해요
- 시작할 때 `SENT_LOG`를 비워서 이번 실행의 요청만 검사해요. 로그를 못 읽거나 요청이 0건이면 실패로 쳐요
- 실패하면 표의 "실제" 칸에 받은 응답이 나와요. 이번 실행에서 받은 입실 번호는 `[입실 번호]`로 가려요

## 한계
- 가짜 모델은 낱말로 도구를 고르므로, 모델이 인자를 맞게 넣는지는 확인하지 않아요 (#9)
- 11/3 로컬 실행 결과: 11개 모두 통과 (core develop `15c6ce3`, mcp-server #40, agent #41)
- 11/3 리뷰 반영 뒤 다시 실행: 11개 모두 통과 (develop + #33·#34·#40·#41 합친 상태, local 프로필). 남은 예약을 하나 만들어 두고 돌려도 시작 정리로 11개 통과
