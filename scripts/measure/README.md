# 성공률 측정 (#9)

같은 질문 세트를 실제 모델(Gemini, 나중에 Ollama)에 보내 **맞는 도구를 골랐는지**로 성공률을 재요. 발표의 "결과 숫자 1개"가 여기서 나와요.
리허설(`scripts/rehearsal`)은 가짜 모델로 전체 길을 검사하고, 여기는 진짜 모델의 판단을 재요.

## 무엇을 재나
| 파일 | 하는 일 |
|---|---|
| `questions.json` | 질문 12개와 기대(어느 도구를 불러야 하는지, 확인 카드가 나와야 하는지, 답에 있어야 할 말). 기대가 여러 개면 하나만 맞아도 통과 |
| `measure.py` | 질문마다 새 대화로 보내고(`DELETE /api/agent/chat/history`) 결과의 `tools`(부른 도구 이름)·`confirmations`·`reply`로 판정. Markdown 표를 `results/`에 남겨요 |

- 쓰기 도구는 **확인 카드까지만** 나와요. [확인]은 누르지 않으니 예약이 생기지 않아요
- 결과 파일에는 질문·판정·도구 이름·걸린 시간·답 앞 80자(6자리 이상 숫자는 가림)만 남아요. 토큰은 안 남아요
- 표준 라이브러리만 써요. `python3 -I`로 돌려요

## 실행
```bash
# 서버(Gemini)에서: 로그인 토큰은 환경 변수로만. 채팅·파일·커밋에 붙이지 마세요
AGENT=https://dev-mspace.java21.net TOKEN='<내 로그인 토큰>' MODEL_LABEL=gemini-3.5-flash-lite \
  python3 -I scripts/measure/measure.py --runs 3

# 로컬(local 프로필, X-User-Id): 테스트 계정만
AGENT=http://localhost:8091 USER_ID=1 MODEL_LABEL=gemini python3 -I scripts/measure/measure.py

# 몇 개만
python3 -I scripts/measure/measure.py --only Q01,Q05
```
- 토큰은 앱에서 로그인한 뒤 브라우저 개발자 도구 → Application → Local Storage(또는 네트워크 탭의 `Authorization` 헤더)에서 복사해요 (확인 필요: front가 어디에 두는지는 재웅 front 코드 기준)
- Gemini 무료 한도가 하루 500회라 12문항 × 3회 = 36회(도구 호출 포함 약 100회)면 충분해요
- 결과 `results/<날짜>.md`를 `docs/일정.md` 측정 항목과 트러블슈팅에 옮겨 적어요. 실패한 질문은 시스템 프롬프트나 도구 설명을 고치고 다시 재요

## 판정 규칙 (questions.json)
| 키 | 뜻 |
|---|---|
| `tools_any` | 이 중 하나는 불렀어야 함 |
| `tools_none` | 이 도구는 부르면 안 됨 (예: "내일 2시"에 `room_status`) |
| `no_tool` | 도구를 하나도 안 불러야 함 (인사, 범위 밖) |
| `confirm_tool` | 확인 카드의 도구 이름 |
| `needs_participants` | 카드가 받을 학번 수 (3명 → 2) |
| `reply_any` | 답에 이 말 중 하나 |

## 한계
- 도구 **인자**(날짜·시각)가 맞는지는 안 봐요. 리허설이나 눈으로 확인해요
- Q06·Q09는 계정 상태(예약 있음/없음, 빈 방 있음/없음)에 따라 기대가 둘이라 둘 중 하나면 통과예요
- 같은 질문을 3회 돌리면 모델의 흔들림이 보여요. 1회만 돌리면 운이에요
