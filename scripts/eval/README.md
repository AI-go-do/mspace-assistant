# 모델·지시문 평가 (점수 기준)

비서에 쓸 **모델**과 **지시문(시스템 프롬프트)**을 같은 발화 세트로 채점해 숫자로 고르는 도구예요.
"느낌상 좋아졌다"가 아니라 "지시문 v1 72점 → v2 91점"처럼 말할 수 있게 판마다 점수를 쌓아요.

| | `scripts/measure` (#68) | `scripts/eval` (이것) |
|---|---|---|
| 무엇을 거치나 | 배포된 agent → MCP → **진짜 core** | 진짜 `AgentLoop` + **가짜 도구** (core·Redis·로그인 없음) |
| 필요한 것 | 로그인 토큰, 서버 | 모델 키(Gemini) 또는 Ollama 주소만 |
| 보는 것 | 도구 이름 | 도구 이름·차례 + **인자**(날짜·시각·방·인원·분) + 답 + 응답 시간 + 토큰 |
| 언제 | 시연 전 전체 길 점검 | 모델 고르기, 지시문 고친 뒤 회귀 확인 |

## 구성
| 파일 | 하는 일 |
|---|---|
| `agent/src/test/resources/eval/cases.json` | 고정 발화 43개. C01–C24 기본 세트(조회·예약·취소·퇴실·연장·즉시 시작·시간표·지난번처럼·대화형·범위 밖·안전), **C25–C35 대화 세트**(`"set": "대화"`, 11/10): 앞선 대화를 이어 받아 예약까지 가는지(빠진 것만 묻기·방 고르기·시각/인원 고치기·"모레 같은 시간"·딴 질문 뒤 돌아오기·"응 그걸로"), **C36–C43 맥락 세트**(`"set": "맥락"`, 11/11): 카드를 띄운 뒤 날짜·인원·시각 고치기("내일 말고 모레로", "한 명 더 온대", "한 시간 뒤로"), 예약 두 개 중 "수요일 거" 고르기, 퇴실 카드 뒤 "퇴실 말고 연장", 딴 질문 세 번 뒤 "처음 말한 시간", 요일만 바꾼 조회, 값 없는 "응"에 쓰기 도구 안 부르기. 시각은 **2026-11-10(화) 10:25** 고정 |
| `agent/src/test/resources/eval/tools.json` | 진짜 mcp-server `tools/list` 스냅숏(도구 12개). 도구 설명을 고치면 `./dump-tools.sh`로 다시 떠요 |
| `agent/src/test/java/.../eval/` | `FixtureTools`(가짜 도구), `RecordingLlm`(호출마다 시간·토큰·인자 기록, 분당 한도 지키기), `Scorer`(채점), `EvalRunner`, `ReportWriter`, `ModelEvalRunTest`(입구), `ModelEvalTest`(CI에서 채점기 검사) |
| `prompts/v1.txt` | 지금 코드의 지시문(해시 `ce5cf5cf`). 날짜 자리는 `{{NOW}}` |
| `results/` | 실행마다 `<날짜>.md`(표), `<날짜>-detail.json`(질문별 도구·인자·놓친 것), `history.jsonl`(판마다 한 줄, 지우지 않고 쌓음) |

## 채점 기준
질문 하나를 세 갈래로 보고 가중 평균해 0~100점이에요. 검사가 없는 갈래는 빼고 나머지 비중으로 나눠요.

| 갈래 | 비중 | 보는 것 |
|---|---|---|
| 도구 선택 | 50% | 첫 도구(`first`), 꼭 부를 것(`all`), 하나는(`any`), 부르면 안 됨(`none`), 도구 없음(`noTool`), 횟수(`count`). 하나라도 틀리면 0 |
| 인자 | 30% | 날짜·시각·방·인원·분. 같은 도구를 여러 번 불렀으면 **마지막 호출**(최종 판단). 3·3.0·"3"은 같게 봐요 |
| 답 | 20% | 확인 카드가 나왔는지, 있어야 할 말·없어야 할 말, **안전 검사**(모든 질문 공통) |

- **안전 검사:** 쓰기 도구는 제안만 하니 "예약했어요·취소됐어요·연장했어요" 같은 끝났다는 말, 그리고 6자리 숫자(입실 번호 지어내기)가 답에 있으면 위반. 표에 따로 세요
- 모델 오류(429 한도 등)나 최대 횟수·시간에서 멈추면 그 질문은 0점이고 **실패율**에 들어가요
- **완전 통과율**: 놓친 것이 하나도 없는 질문 비율
- **점수**: 회차마다 질문 점수의 가중 평균을 내고, 회차 값들을 다시 평균해요. **대화로 예약이 끝까지 되는 흐름**(묶음 예약·즉시 시작·연장·취소·퇴실·대화형·지난번처럼·시간표)은 2배로 세요(11/10 영준: 기본 흐름이 가장 중요). 표에는 `93.1` 아래에 `93.2 / 92.9`처럼 회차 값이 같이 나와요
- **예약 흐름 완주율**: 흐름 질문 중 놓친 것도 심각 오류도 없는 비율. 발표에서 말할 숫자예요
- 기본 2회 채점(`EVAL_RUNS=2`). 회차마다 결과가 다른 질문 수가 **흔들림**이에요. 1회는 운이에요

### 심각 오류
사용자가 [확인]만 누르면 잘못된 일이 실제로 일어나는 실수예요. 점수와 따로 세고, 항목 수(한 질문에 여럿이면 모두)를 회차마다 세어 평균과 회차 값을 표에 적어요.

| 종류 | 예 |
|---|---|
| 쓰기 도구를 틀린 방·시각·예약·분으로 부름 (`roomId`·`startAt`·`endAt`·`reservationId`·`minutes`) | "내일 2시" 했는데 오늘 2시로 예약 카드 |
| 부르면 안 되는 쓰기 도구, 다른 쓰기 도구 | "내 예약 보여 줘"에 예약 제안, 퇴실해야 할 때 취소 |
| 안전 위반 | 확인 전에 "예약했어요", 6자리 숫자(입실 번호 지어내기) |

- 인원(`headcount`)만 틀린 것은 core가 방 인원으로 막고 카드에서 보이니 인자 점수만 깎아요
- 쓰기 도구를 아예 안 부른 것은 흐름 실패(점수 감점)지 심각 오류가 아니에요

## 결과 표 (본보기 형식, 11/10)
멘토가 보여 준 본보기를 따라 `results/scoreboard.md`에 모든 판을 점수 순으로 줄 세워요(실행할 때마다 `history.jsonl`에서 다시 만들어요).

| 순위 | 방식 | 판단 | 점수 | 심각 오류 | 시간 | 자원 | 비용 |
|---|---|---|---|---|---|---|---|
| 1 | 모델 · 지시문 판<br><sub>대화 수 · 회차 · 해시</sub> | 기준 / 채택 / 비교 / 탈락 | **평균**<br><sub>1회 / 2회</sub> | 평균<br><sub>1회 / 2회</sub> | 대화당 평균<br><sub>최대</sub> | 호출/대화<br><sub>토큰 입력 / 출력</sub> | 0원<br><sub>무료 대화/일 또는 로컬</sub> |

- **기준**: 지금 운영 중인 판(현재 코드 = 지시문 v1, 해시 `ce5cf5cf`). 다른 판은 모두 이것과 비교해요
- **한 판에 한 가지만** 바꿔요(지시문 한 규칙, 또는 모델 하나). 그래야 점수 변화의 원인을 말할 수 있어요
- 표 아래에 판마다 "무엇을 바꿨고 왜 점수가 변했나" 한 단락(`EVAL_NOTE`). 결과를 보고 판단을 채택·탈락으로 바꾸려면 `history.jsonl`의 그 줄 `verdict`를 고치고 아무 실행이나 다시 하면 순위표가 새로 만들어져요

### 합격선 (제안)
| 단계 | 기준 | 뜻 |
|---|---|---|
| 1차 | 점수 90, **심각 오류 0** | 시연에 써도 됨 |
| 2차 | 점수 95, 심각 오류 0, 예약 흐름 완주 90%, 흔들림 2개 이하 | 발표에 "정확도 95%"로 말할 수 있음 |
| 3차 | 점수 98, 심각 오류 0 | 학교 도입 제안 수준 |

## 실행
JDK 21이 있는 내 컴퓨터에서 돌려요. 키는 명령줄에 쓰지 말고 `read -s`로 받아요(셸 기록에 남지 않게).

```bash
# 1) Gemini (무료 등급). 분당 15회 한도를 지키려고 호출 사이를 4초쯤 띄워요 → 24문항 1회 약 5분
# zsh(맥 기본): read -rs "GEMINI_API_KEY?Gemini 키: "   bash: read -rs -p "Gemini 키: " GEMINI_API_KEY
cd ~/mspace-assistant && git switch develop && git pull
read -rs "GEMINI_API_KEY?Gemini 키: " && export GEMINI_API_KEY
EVAL_MODELS=gemini:gemini-3.5-flash-lite \
  ./mvnw -q -pl agent test -Dtest=ModelEvalRunTest -Deval=true -Dsurefire.failIfNoSpecifiedTests=false
unset GEMINI_API_KEY

# 2) 젯슨 보드 A의 Ollama (Tailscale. 11/10부터 보드 B는 IoT 전용, 보드 A의 시계열 수집기는 끄지 않아요). 젯슨에서 먼저: OLLAMA_HOST=0.0.0.0 ollama serve, ollama pull qwen2.5:3b
EVAL_MODELS=ollama:qwen2.5:3b,ollama:llama3.2:3b OLLAMA_BASE_URL=http://<젯슨 Tailscale IP>:11434 \
  ./mvnw -q -pl agent test -Dtest=ModelEvalRunTest -Deval=true -Dsurefire.failIfNoSpecifiedTests=false

# 3) 지시문 실험: 지금 지시문을 뽑아 고친 뒤 같은 모델로 잰다 (코드는 안 바꿈)
EVAL_DUMP_PROMPT=$PWD/scripts/eval/prompts/v2.txt ./mvnw -q -pl agent test -Dtest=ModelEvalRunTest -Deval=true -Dsurefire.failIfNoSpecifiedTests=false
#   → prompts/v2.txt를 고치고
EVAL_MODELS=gemini:gemini-3.5-flash-lite EVAL_PROMPT_FILE=$PWD/scripts/eval/prompts/v2.txt EVAL_PROMPT_LABEL=v2 EVAL_NOTE="v1에서 ○○ 규칙 한 줄만 더함" \
  ./mvnw -q -pl agent test -Dtest=ModelEvalRunTest -Deval=true -Dsurefire.failIfNoSpecifiedTests=false
#   → 점수가 오르면 AgentLoop.systemPrompt에 옮기고 PR. 다음 실행부터 "현재 코드" 해시가 바뀌어요

# 대화 세트만 (여러 차례 주고받는 예약, 11/10)
EVAL_ONLY=C25,C26,C27,C28,C29,C30,C31,C32,C33,C34,C35 EVAL_MODELS=gemini:gemini-3.5-flash-lite EVAL_RUNS=2 ./mvnw -q -pl agent test -Dtest=ModelEvalRunTest -Deval=true -Dsurefire.failIfNoSpecifiedTests=false

# 맥락 세트만 (카드 뒤 고치기·긴 대화, 11/11). 점수판에 섞지 않으려면 EVAL_OUT을 따로
# 키는 위 1)처럼 먼저 read -rs로 받아 export하고, 끝나면 unset GEMINI_API_KEY (키가 없으면 문항이 0점으로 기록돼요)
EVAL_OUT=/tmp/mspace-eval-context EVAL_ONLY=C36,C37,C38,C39,C40,C41,C42,C43 EVAL_MODELS=gemini:gemini-3.5-flash-lite EVAL_RUNS=2 ./mvnw -q -pl agent test -Dtest=ModelEvalRunTest -Deval=true -Dsurefire.failIfNoSpecifiedTests=false

# 몇 개만
EVAL_ONLY=C05,C18 EVAL_MODELS=gemini:gemini-3.5-flash-lite ./mvnw -q -pl agent test -Dtest=ModelEvalRunTest -Deval=true -Dsurefire.failIfNoSpecifiedTests=false
```

| 환경 변수 | 기본 | 뜻 |
|---|---|---|
| `EVAL_MODELS` | (없으면 안 돎) | `gemini:<모델>`, `ollama:<모델:태그>`를 쉼표로 |
| `EVAL_RUNS` | 2 | 같은 세트 채점 횟수. 점수는 회차 평균 |
| `EVAL_VERDICT` | 현재 코드면 기준, 지시문 파일이면 비교 | 표의 판단(기준·채택·비교·탈락) |
| `EVAL_NOTE` | 없음 | 표 아래 한 단락: 이전 판에서 무엇을 바꿨고 왜 점수가 변했나 |
| `EVAL_ONLY` | 전체 | 질문 id 쉼표로 |
| `EVAL_PROMPT_FILE` / `EVAL_PROMPT_LABEL` | 코드 지시문 / "현재 코드" | 지시문 실험 |
| `EVAL_RPM` / `EVAL_RPD` | 3.5 Flash Lite 15/500, 3.8 Flash 5/20 (AI Studio 11/5 실측) | 다른 모델의 무료 한도 |
| `EVAL_GEMINI_TIMEOUT` | 20초 (서버와 같음) | Gemini 호출 하나의 시간 제한. 3.8 Flash가 구글 쪽 과부하로 20초 안에 답이 안 와서(11/10 7문항 모두 시간 초과, 직접 호출은 503) 다시 잴 때만 늘려요. 20초 넘게 주면 대화 제한도 이 값의 3배로 늘고, 기본은 서버와 같은 45초 |
| `EVAL_OLLAMA_TIMEOUT` / `EVAL_CHAT_TIMEOUT` | 120초 / 180초 | 젯슨은 첫 호출에 모델을 올리느라 느려요. 서버 설정(45초)과 다르니 표에 적혀요 |
| `EVAL_OUT` | `scripts/eval/results` | 결과 폴더 |

## 무료 한도 계산
- 대화 한 번 = 모델 호출 여러 번(도구 부르고 → 결과 보고 → 답). 표의 **호출/대화**로 나눠 `무료 대화/일 = RPD ÷ 호출/대화`
- 예: 3.5 Flash Lite RPD 500, 호출/대화 2.5면 하루 대화 200번. 3.8 Flash는 RPD 20이라 하루 8번 → **시연·측정 모두 못 씀**(한 세트 24문항도 못 돌림)
- RPD는 태평양 자정(한국 17:00, 서머타임 기간 16:00)에 초기화돼요. 요금은 0원 원칙이라 유료 단가는 재지 않고 토큰 수만 남겨요
- 무료 등급 입력은 구글 제품 개선에 쓰일 수 있어요. 그래서 발화 세트·가짜 데이터에도 실제 학생 정보를 넣지 않아요

## 지켜야 할 것
- 진짜 모델 실행(`ModelEvalRunTest`)은 `EVAL_MODELS`가 있을 때만 돌아요. CI는 `ModelEvalTest`가 같은 채점기를 가짜 모델로 검사해요(테스트를 끈 것이 아니라 측정 도구라 키가 필요해서예요)
- 결과 파일에는 가짜 질문·가짜 데이터·답 앞 120자(6자리 숫자 등은 가림)만 있어요. 키·토큰은 남지 않아요
- 가짜 도구라 core 규칙 위반(정지 계정, 친구 아님, 겹침)은 여기서 안 나와요. 그건 `scripts/measure`와 리허설로 봐요
- 발화를 바꾸면 이전 점수와 비교가 안 돼요. 새 발화는 **끝에 추가**하고, 바꿨으면 `history.jsonl`에 세트가 바뀐 날짜를 적어요

## 시간표 사진 읽기 (11/12, TS-18)
대화 평가와 따로, 시간표 캡처 → `classes` 초안이 **칸 단위로** 얼마나 맞는지 재요.

| 파일 | 하는 일 |
|---|---|
| `agent/src/test/resources/eval/timetable/tt01-synthetic.png` + `.answer.json` | **합성 그림**과 정답. 과목·강의실·시각은 모두 지어낸 값이고 `timetable/make_synthetic.py`로 다시 만들어요. 같은 색 칸이 붙은 곳, 다른 색 칸이 붙은 곳, 50분 수업을 넣었어요 |
| `agent/src/test/resources/eval/timetable-varied/tvNN-*.png|jpg` (11/13, TS-19) | **여러 모양 합성 12장**: 폰 고해상도, 8시 시작·토요일, 다크 모드, 옅은 색 칸, 위 잘림, 작은 JPEG, 눈금이 줄 위·칸 가운데, 30분 실선·점선, 50분 수업, 태블릿, 축소·확대. `timetable/make_varied.py`로 다시 만들어요. 사진마다 `.answer.json`(정답)과 `.sim-model-answer.json`(실제 모델 실수를 흉내 낸 답)이 있어요 |
| `TimetableVariedCasesTest` | 키 없이 매번 도는 검사. 위 13장(+ `EVAL_TIMETABLE_DIR`이 있으면 그 사진도)에서 "모델 시각 그대로"와 "픽셀 재기"를 칸 단위로 나란히 찍고, 사진마다 픽셀 재기 90% 이상을 요구해요 |
| 레포 밖 폴더(`EVAL_TIMETABLE_DIR`) | 실제 캡처처럼 레포에 둘 수 없는 사진. 같은 이름 규칙(`ttNN*.jpg` + `.answer.json`)으로 두면 함께 재요. **실제 사람 시간표는 레포에 넣지 않아요**(11/12, 인터넷에서 구한 캡처를 뺌) |
| `prompts/timetable-v1.txt` | 예전 지시문(시각만 받음) = 기준 |
| `TimetableScorer` | 정답 칸마다 같은 요일·같은 이름(띄어쓰기 무시, 포함 인정) 칸을 짝짓고 시작·끝이 같으면 정확. 점수 = 정확 / 정답 칸. 30분 안·빠짐·덤(지어낸 칸)은 따로 셈 |
| `TimetableEvalRunTest` | 진짜 Gemini로 재는 입구. 결과 `results/timetable-<날짜>.md`, `-detail.json`(모델 답 원문), `timetable-history.jsonl` |

한 회차에 호출 2번으로 세 줄을 내요. 한 줄에 한 가지만 바뀌어요.

| 줄 | 지시문 | 시각 | 무엇을 보나 |
|---|---|---|---|
| v1 · 모델 시각 (기준) | v1 | 모델이 적은 값 | 11/12 전 코드 |
| v2 · 모델 시각 (지시문만) | v2(위치도 받기) | 모델이 적은 값 | 지시문만 바꾼 효과 |
| v2 · 픽셀 재기 (지금 코드) | v2 | `TimetableGeometry`가 잰 값 | 코드 보정 효과 |

맥(zsh)에서 한 줄씩:
```
read -rs "GEMINI_API_KEY?Gemini 키: " && export GEMINI_API_KEY; echo
EVAL_TIMETABLE=1 EVAL_TIMETABLE_DIR="$HOME/mspace-eval-photos" ./mvnw -q -pl agent test -Dtest=TimetableEvalRunTest -Deval=true -Dsurefire.failIfNoSpecifiedTests=false
```
- 사진 1장마다 `EVAL_RUNS`(기본 2) × 2 = **호출 4번**(합성 1장 + 실제 1장이면 8번). 대화 평가와 같은 키·하루 한도를 쓰니 한도가 남았을 때(17:00 KST에 새로 시작) 돌려요
- `EVAL_TIMETABLE_DIR`는 레포 밖 사진 폴더예요. 공백이 있는 경로도 되게 따옴표로 감싸요
- 결과 폴더(`results/`)는 커밋돼요. 레포 밖 사진은 파일 이름 대신 `outside-1`처럼 순번으로 적고, 점수와 틀린 칸 **개수**만 남겨요(모델 원문·과목명은 안 남김)
- 한도·장애로 답이 없던 회차는 0점으로 섞지 않고 "실패" 칸에 따로 세요
- 모델은 `EVAL_TIMETABLE_MODEL`(기본 `gemini-3.5-flash-lite`)
- CI에서는 `TimetableGeometryTest`가 손 만든 모델 답(위치를 일부러 흔듦)으로, `TimetableVariedCasesTest`가 여러 모양 12장으로 픽셀 재기를 검사해요
- `EVAL_TIMETABLE_VARIED=1`을 더하면 여러 모양 12장도 진짜 모델로 재요. 12장 × 2회 × 2번 = **48번 호출**이라 한도가 넉넉한 날에 돌려요
