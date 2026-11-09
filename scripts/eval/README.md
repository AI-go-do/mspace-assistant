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
| `agent/src/test/resources/eval/cases.json` | 고정 발화 24개(조회·예약·취소·퇴실·연장·즉시 시작·시간표·지난번처럼·대화형·범위 밖·안전). 시각은 **2026-11-10(화) 10:25** 고정 |
| `agent/src/test/resources/eval/tools.json` | 진짜 mcp-server `tools/list` 스냅숏(도구 12개). 도구 설명을 고치면 `./dump-tools.sh`로 다시 떠요 |
| `agent/src/test/java/.../eval/` | `FixtureTools`(가짜 도구), `RecordingLlm`(호출마다 시간·토큰·인자 기록, 분당 한도 지키기), `Scorer`(채점), `EvalRunner`, `ReportWriter`, `ModelEvalRun`(입구), `ModelEvalTest`(CI에서 채점기 검사) |
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
- **완전 통과율**: 놓친 것이 하나도 없는 질문 비율. **종합 점수**: 질문 점수 평균
- 같은 세트를 `EVAL_RUNS=3`으로 돌리면 **흔들림**(회차마다 결과가 다른 질문 수)이 보여요. 1회는 운이에요

### 합격선 (제안)
| 단계 | 기준 | 뜻 |
|---|---|---|
| 1차 | 종합 90점, 안전 위반 0 | 시연에 써도 됨 |
| 2차 | 종합 95점, 완전 통과 90%, 흔들림 2개 이하 | 발표에 "정확도 95%"로 말할 수 있음 |
| 3차 | 종합 98점 | 학교 도입 제안 수준 |

## 실행
JDK 21이 있는 내 컴퓨터에서 돌려요. 키는 명령줄에 쓰지 말고 `read -s`로 받아요(셸 기록에 남지 않게).

```bash
# 1) Gemini (무료 등급). 분당 15회 한도를 지키려고 호출 사이를 4초쯤 띄워요 → 24문항 1회 약 5분
read -rs -p "Gemini 키: " GEMINI_API_KEY && export GEMINI_API_KEY
EVAL_MODELS=gemini:gemini-3.5-flash-lite EVAL_RUNS=3 \
  ./mvnw -q -pl agent test -Dtest=ModelEvalRun -Dsurefire.failIfNoSpecifiedTests=false
unset GEMINI_API_KEY

# 2) 젯슨 보드 B의 Ollama (Tailscale). 젯슨에서 먼저: OLLAMA_HOST=0.0.0.0 ollama serve, ollama pull qwen2.5:3b
EVAL_MODELS=ollama:qwen2.5:3b,ollama:llama3.2:3b OLLAMA_BASE_URL=http://<젯슨 Tailscale IP>:11434 \
  ./mvnw -q -pl agent test -Dtest=ModelEvalRun -Dsurefire.failIfNoSpecifiedTests=false

# 3) 지시문 실험: 지금 지시문을 뽑아 고친 뒤 같은 모델로 잰다 (코드는 안 바꿈)
EVAL_DUMP_PROMPT=$PWD/scripts/eval/prompts/v2.txt ./mvnw -q -pl agent test -Dtest=ModelEvalRun -Dsurefire.failIfNoSpecifiedTests=false
#   → prompts/v2.txt를 고치고
EVAL_MODELS=gemini:gemini-3.5-flash-lite EVAL_PROMPT_FILE=$PWD/scripts/eval/prompts/v2.txt EVAL_PROMPT_LABEL=v2 EVAL_RUNS=3 \
  ./mvnw -q -pl agent test -Dtest=ModelEvalRun -Dsurefire.failIfNoSpecifiedTests=false
#   → 점수가 오르면 AgentLoop.systemPrompt에 옮기고 PR. 다음 실행부터 "현재 코드" 해시가 바뀌어요

# 몇 개만
EVAL_ONLY=C05,C18 EVAL_MODELS=gemini:gemini-3.5-flash-lite ./mvnw -q -pl agent test -Dtest=ModelEvalRun -Dsurefire.failIfNoSpecifiedTests=false
```

| 환경 변수 | 기본 | 뜻 |
|---|---|---|
| `EVAL_MODELS` | (없으면 안 돎) | `gemini:<모델>`, `ollama:<모델:태그>`를 쉼표로 |
| `EVAL_RUNS` | 1 | 같은 세트 반복 횟수 |
| `EVAL_ONLY` | 전체 | 질문 id 쉼표로 |
| `EVAL_PROMPT_FILE` / `EVAL_PROMPT_LABEL` | 코드 지시문 / "현재 코드" | 지시문 실험 |
| `EVAL_RPM` / `EVAL_RPD` | 3.5 Flash Lite 15/500, 3.8 Flash 5/20 (AI Studio 11/5 실측) | 다른 모델의 무료 한도 |
| `EVAL_OLLAMA_TIMEOUT` / `EVAL_CHAT_TIMEOUT` | 120초 / 180초 | 젯슨은 첫 호출에 모델을 올리느라 느려요. 서버 설정(45초)과 다르니 표에 적혀요 |
| `EVAL_OUT` | `scripts/eval/results` | 결과 폴더 |

## 무료 한도 계산
- 대화 한 번 = 모델 호출 여러 번(도구 부르고 → 결과 보고 → 답). 표의 **호출/대화**로 나눠 `무료 대화/일 = RPD ÷ 호출/대화`
- 예: 3.5 Flash Lite RPD 500, 호출/대화 2.5면 하루 대화 200번. 3.8 Flash는 RPD 20이라 하루 8번 → **시연·측정 모두 못 씀**(한 세트 24문항도 못 돌림)
- RPD는 태평양 자정(한국 17:00, 서머타임 기간 16:00)에 초기화돼요. 요금은 0원 원칙이라 유료 단가는 재지 않고 토큰 수만 남겨요
- 무료 등급 입력은 구글 제품 개선에 쓰일 수 있어요. 그래서 발화 세트·가짜 데이터에도 실제 학생 정보를 넣지 않아요

## 지켜야 할 것
- 진짜 모델 실행(`ModelEvalRun`)은 `EVAL_MODELS`가 있을 때만 돌아요. CI는 `ModelEvalTest`가 같은 채점기를 가짜 모델로 검사해요(테스트를 끈 것이 아니라 측정 도구라 키가 필요해서예요)
- 결과 파일에는 가짜 질문·가짜 데이터·답 앞 120자(6자리 숫자 등은 가림)만 있어요. 키·토큰은 남지 않아요
- 가짜 도구라 core 규칙 위반(정지 계정, 친구 아님, 겹침)은 여기서 안 나와요. 그건 `scripts/measure`와 리허설로 봐요
- 발화를 바꾸면 이전 점수와 비교가 안 돼요. 새 발화는 **끝에 추가**하고, 바꿨으면 `history.jsonl`에 세트가 바뀐 날짜를 적어요
