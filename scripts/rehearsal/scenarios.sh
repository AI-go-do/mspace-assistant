#!/usr/bin/env bash
# 시연 리허설: 채팅 → agent → MCP 서버 → core를 가짜 모델로 한 바퀴 돌리고 표로 판정한다.
# 준비: core(X-User-Id 켜기)·mcp-server·agent(LLM_PROVIDER=ollama, OLLAMA_BASE_URL=가짜 모델)·fake_ollama.py
# 사용: AGENT=http://localhost:18091 OWNER=4 OTHER=2 P1=29990002 P2=29990003 SENT_LOG=sent.jsonl ./scenarios.sh
# OWNER는 끝나지 않은 예약이 없는 테스트 계정, P1·P2는 함께 쓸 테스트 학번. 실제 학생 정보는 쓰지 않는다.
set -u
AGENT=${AGENT:-http://localhost:18091}; OWNER=${OWNER:-4}; OTHER=${OTHER:-2}
P1=${P1:-29990002}; P2=${P2:-29990003}; SENT_LOG=${SENT_LOG:-sent.jsonl}
pass=0; fail=0
row() { if [ "$2" = ok ]; then pass=$((pass+1)); printf '| %s | 통과 | %s |\n' "$1" "$3"; else fail=$((fail+1)); printf '| %s | **실패** | %s |\n' "$1" "$3"; fi; }
chat() { curl -s -X POST "$AGENT/api/agent/chat" -H "X-User-Id: $1" -H 'Content-Type: application/json' \
  -d "$(python3 -c 'import json,sys;print(json.dumps({"message":sys.argv[1],"history":[]}))' "$2")"; }
confirm() { curl -s -X POST "$AGENT/api/agent/confirmations/$2" -H "X-User-Id: $1" -H 'Content-Type: application/json' -d "$3"; }
j() { python3 -c "import json,sys;d=json.load(sys.stdin);print(eval(sys.argv[1]))" "$1"; }

echo '| 시나리오 | 판정 | 실제 |'; echo '|---|---|---|'
r=$(chat "$OWNER" "내일 오후 2시 빈방 있어?"); n=$(echo "$r" | j 'd.get("toolCalls")')
[ "$n" = 1 ] && row "내일 오후 2시 빈방" ok "find_available_rooms 1번" || row "내일 오후 2시 빈방" no "$r"
r=$(chat "$OWNER" "수업 끝나고 2시간 잡아 줘"); [ "$(echo "$r" | j 'd.get("toolCalls")')" = 0 ] \
  && row "수업 끝나고 2시간" ok "끝나는 시각을 되물음" || row "수업 끝나고 2시간" no "$r"
r=$(chat "$OWNER" "내일 2시부터 2시간 2번 방 3명으로 예약해 줘"); tok=$(echo "$r" | j 'd["confirmations"][0]["token"] if d.get("confirmations") else ""')
[ -n "$tok" ] && row "예약 제안 → 확인 카드" ok "$(echo "$r" | j 'd["confirmations"][0]["summary"]')" || row "예약 제안 → 확인 카드" no "$r"
r=$(confirm "$OTHER" "$tok" "{\"participantStudentIds\":[\"$P1\",\"$P2\"]}"); [ "$(echo "$r" | j 'd.get("code")')" = CONFIRMATION_NOT_FOUND ] \
  && row "남이 확인 토큰 사용" ok "CONFIRMATION_NOT_FOUND" || row "남이 확인 토큰 사용" no "$r"
r=$(confirm "$OWNER" "$tok" "{\"participantStudentIds\":[\"$P1\",\"$P2\"]}"); rid=$(echo "$r" | j 'd.get("reservationId","")')
[ "$(echo "$r" | j 'd.get("status")')" = BOOKED ] && row "본인이 [확인]" ok "예약 $rid BOOKED" || row "본인이 [확인]" no "$r"
r=$(confirm "$OWNER" "$tok" "{\"participantStudentIds\":[\"$P1\",\"$P2\"]}"); [ "$(echo "$r" | j 'd.get("code")')" = CONFIRMATION_USED ] \
  && row "같은 토큰 다시" ok "CONFIRMATION_USED" || row "같은 토큰 다시" no "$r"
r=$(chat "$OWNER" "내 입실 번호 알려 줘"); [ "$(echo "$r" | j 'len(d.get("accessCodeCards",[]))')" = 1 ] && ! echo "$r" | grep -q accessCode\" \
  && row "입실 번호(대표자)" ok "화면 카드만, 답에 번호 없음" || row "입실 번호(대표자)" no "$r"
r=$(curl -s "$AGENT/api/agent/reservations/$rid/access-code" -H "X-User-Id: $OTHER"); [ "$(echo "$r" | j 'd.get("code")')" = NOT_RESERVATION_OWNER ] \
  && row "입실 번호(참여자, 화면 API)" ok "NOT_RESERVATION_OWNER" || row "입실 번호(참여자, 화면 API)" no "$r"
r=$(chat "$OWNER" "내 예약 취소해 줘"); tok=$(echo "$r" | j 'd["confirmations"][0]["token"] if d.get("confirmations") else ""')
r=$(confirm "$OWNER" "$tok" '{}'); [ "$(echo "$r" | j 'd.get("status")')" = CANCELED ] \
  && row "내 예약 취소해 줘 → [확인]" ok "CANCELED" || row "내 예약 취소해 줘 → [확인]" no "$r"
r=$(chat "$OWNER" "무한 반복해"); [ "$(echo "$r" | j 'd.get("stoppedByLimit")')" = True ] \
  && row "도구 호출 최대 횟수" ok "$(echo "$r" | j 'd.get("toolCalls")')번에서 멈춤" || row "도구 호출 최대 횟수" no "$r"
leaks=$(grep -cE "$P1|$P2|\"c_|accessCode|X-User-Id" "$SENT_LOG" 2>/dev/null || true)
[ "${leaks:-0}" = 0 ] && row "외부 AI로 나간 내용" ok "학번·확인 토큰·accessCode 0건 ($(wc -l < "$SENT_LOG")번 요청)" || row "외부 AI로 나간 내용" no "$leaks줄에서 발견"
echo; echo "통과 $pass, 실패 $fail"; [ "$fail" = 0 ]
