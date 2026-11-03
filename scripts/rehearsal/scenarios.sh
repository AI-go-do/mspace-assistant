#!/usr/bin/env bash
# 시연 리허설: 채팅 → agent → MCP 서버 → core를 가짜 모델로 한 바퀴 돌리고 표로 판정한다.
# 준비: core(MSPACE_DEV_HEADER=true)·mcp-server·agent(둘 다 SPRING_PROFILES_ACTIVE=local, agent는 LLM_PROVIDER=ollama,
#       OLLAMA_BASE_URL=가짜 모델)·fake_ollama.py. 자세한 건 README.md
# 사용: CORE=http://localhost:18080 MCP=http://localhost:18090 AGENT=http://localhost:18091 OWNER=4 OTHER=2 P1=29990002 P2=29990003 SENT_LOG=sent.jsonl ./scenarios.sh
# OWNER·OTHER는 테스트 계정, P1은 OTHER의 학번(참여자 검사용), P2는 다른 테스트 학번. 실제 학생 정보는 쓰지 않는다.
# 시작할 때와 끝날 때(중간에 실패해도) OWNER의 끝나지 않은 예약을 core에서 직접 정리한다. 테스트 계정에만 쓴다.
set -u
CORE=${CORE:-http://localhost:18080}; AGENT=${AGENT:-http://localhost:18091}; OWNER=${OWNER:-4}; OTHER=${OTHER:-2}
P1=${P1:-29990002}; P2=${P2:-29990003}; SENT_LOG=${SENT_LOG:-sent.jsonl}
pass=0; fail=0; code=""
# 실패한 줄의 "실제" 칸에는 응답 원문이 찍힌다. 입실 번호가 섞여 나오지 않게 가린다
row() { if [ "$2" = ok ]; then pass=$((pass+1)); printf '| %s | 통과 | %s |\n' "$1" "$3"; else fail=$((fail+1));
  local shown=$3; [ -n "$code" ] && shown=${shown//$code/[입실 번호]}; printf '| %s | **실패** | %s |\n' "$1" "$shown"; fi; }
# OWNER의 끝나지 않은 예약(BOOKED는 취소, IN_USE는 퇴실)을 정리한다. 앞 실행이 중간에 멈춰 남긴 예약 때문에
# 대표자 동시 1건 규칙(OWNER_HAS_ACTIVE_RESERVATION)에 걸리지 않게 한다
# 조회·취소·퇴실 중 하나라도 실패하면 1을 돌려준다. 시작 정리에 실패하면 시나리오를 돌리지 않고, 끝 정리에 실패하면 실패로 끝낸다
cleanup() {
  local out status ids id verb rc=0
  out=$(curl -s --max-time 5 -w '\n%{http_code}' "$CORE/api/reservations/me" -H "X-User-Id: $OWNER") \
    || { echo "예약 정리: core에 연결하지 못했어요 ($CORE)" >&2; return 1; }
  status=${out##*$'\n'}
  [ "$status" = 200 ] || { echo "예약 정리: 내 예약 조회가 $status 로 실패했어요" >&2; return 1; }
  ids=$(printf '%s' "${out%$'\n'*}" | python3 -c '
import json,sys
rs=json.load(sys.stdin)
rs=rs.get("reservations") if isinstance(rs,dict) else rs
if not isinstance(rs,list): sys.exit(1)
for r in rs:
    if r.get("owner") and r.get("status") in ("BOOKED","IN_USE"): print(r["id"], "cancel" if r["status"]=="BOOKED" else "checkout")') \
    || { echo "예약 정리: 내 예약 응답을 읽지 못했어요" >&2; return 1; }
  while read -r id verb; do
    [ -n "$id" ] || continue
    status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST "$CORE/api/reservations/$id/$verb" -H "X-User-Id: $OWNER")
    case $status in 2??) ;; *) echo "예약 정리: 예약 $id $verb 이 ${status:-연결 실패} 로 실패했어요" >&2; rc=1 ;; esac
  done <<< "$ids"
  return $rc
}
on_exit() { local st=$?; cleanup || { echo "끝 정리 실패: OWNER 예약이 남았을 수 있어요" >&2; [ "$st" = 0 ] && st=1; }; exit "$st"; }
trap on_exit EXIT
chat() { curl -s -X POST "$AGENT/api/agent/chat" -H "X-User-Id: $1" -H 'Content-Type: application/json' \
  -d "$(python3 -c 'import json,sys;print(json.dumps({"message":sys.argv[1],"history":[]}))' "$2")"; }
confirm() { curl -s -X POST "$AGENT/api/agent/confirmations/$2" -H "X-User-Id: $1" -H 'Content-Type: application/json' -d "$3"; }
j() { python3 -c "import json,sys;d=json.load(sys.stdin);print(eval(sys.argv[1]))" "$1"; }

# MCP 서버와 agent가 뜰 때까지 각각 최대 60초 기다린다. 안 뜨면 시나리오를 돌리지 않는다.
MCP=${MCP:-http://localhost:18090}
for svc in "$MCP" "$AGENT"; do
  for _ in $(seq 1 60); do curl -sf --max-time 2 "$svc/actuator/health" >/dev/null && break; sleep 1; done
  curl -sf --max-time 2 "$svc/actuator/health" >/dev/null || { echo "준비되지 않았어요: $svc" >&2; exit 1; }
done
cleanup || { echo "시작 정리에 실패해 시나리오를 돌리지 않아요" >&2; trap - EXIT; exit 1; }
# 이번 실행에서 외부 AI로 나간 요청만 검사하도록 로그를 비운다.
: > "$SENT_LOG" || { echo "SENT_LOG를 비울 수 없어요: $SENT_LOG" >&2; exit 1; }

echo '| 시나리오 | 판정 | 실제 |'; echo '|---|---|---|'
# 어떤 도구를 불렀는지는 응답에 없으므로, 가짜 모델이 받은 마지막 요청의 대화에서 모델이 부른 도구 이름을 읽는다.
tools_called() { tail -n 1 "$SENT_LOG" | python3 -c "import json,sys;d=json.loads(sys.stdin.read() or '{}');print(','.join(c['function']['name'] for m in d.get('messages',[]) for c in m.get('tool_calls',[])))"; }
r=$(chat "$OWNER" "내일 오후 2시 빈방 있어?"); n=$(echo "$r" | j 'd.get("toolCalls")'); called=$(tools_called)
[ "$n" = 1 ] && [ "$called" = find_available_rooms ] && row "내일 오후 2시 빈방" ok "$called 1번" || row "내일 오후 2시 빈방" no "도구 ${called:-없음}, $r"
r=$(chat "$OWNER" "수업 끝나고 2시간 잡아 줘"); [ "$(echo "$r" | j 'd.get("toolCalls")')" = 0 ] && echo "$r" | grep -qF '끝나는 시각' \
  && row "수업 끝나고 2시간" ok "끝나는 시각을 되물음" || row "수업 끝나고 2시간" no "$r"
r=$(chat "$OWNER" "내일 2시부터 2시간 2번 방 3명으로 예약해 줘"); tok=$(echo "$r" | j 'd["confirmations"][0]["token"] if d.get("confirmations") else ""')
[ -n "$tok" ] && row "예약 제안 → 확인 카드" ok "$(echo "$r" | j 'd["confirmations"][0]["summary"]')" || row "예약 제안 → 확인 카드" no "$r"
r=$(confirm "$OTHER" "$tok" "{\"participantStudentIds\":[\"$P1\",\"$P2\"]}"); [ "$(echo "$r" | j 'd.get("code")')" = CONFIRMATION_NOT_FOUND ] \
  && row "남이 확인 토큰 사용" ok "CONFIRMATION_NOT_FOUND" || row "남이 확인 토큰 사용" no "$r"
r=$(confirm "$OWNER" "$tok" "{\"participantStudentIds\":[\"$P1\",\"$P2\"]}"); rid=$(echo "$r" | j 'd.get("reservationId","")')
tok1=$tok; code=$(curl -s "$AGENT/api/agent/reservations/$rid/access-code" -H "X-User-Id: $OWNER" | j 'd.get("accessCode") or ""' 2>/dev/null)
[ "$(echo "$r" | j 'd.get("status")')" = BOOKED ] && row "본인이 [확인]" ok "예약 $rid BOOKED" || row "본인이 [확인]" no "$r"
r=$(confirm "$OWNER" "$tok" "{\"participantStudentIds\":[\"$P1\",\"$P2\"]}"); [ "$(echo "$r" | j 'd.get("code")')" = CONFIRMATION_USED ] \
  && row "같은 토큰 다시" ok "CONFIRMATION_USED" || row "같은 토큰 다시" no "$r"
r=$(chat "$OWNER" "내 입실 번호 알려 줘"); [ -n "$code" ] && [ "$(echo "$r" | j 'len(d.get("accessCodeCards",[]))')" = 1 ] && ! echo "$r" | grep -qF -e 'accessCode"' -e "$code" \
  && row "입실 번호(대표자)" ok "화면 카드만, 답에 번호 없음" || row "입실 번호(대표자)" no "$r"
r=$(curl -s "$AGENT/api/agent/reservations/$rid/access-code" -H "X-User-Id: $OTHER"); [ "$(echo "$r" | j 'd.get("code")')" = NOT_RESERVATION_OWNER ] \
  && row "입실 번호(참여자, 화면 API)" ok "NOT_RESERVATION_OWNER" || row "입실 번호(참여자, 화면 API)" no "$r"
r=$(chat "$OWNER" "내 예약 취소해 줘"); tok=$(echo "$r" | j 'd["confirmations"][0]["token"] if d.get("confirmations") else ""')
tok2=$tok; r=$(confirm "$OWNER" "$tok" '{}'); [ "$(echo "$r" | j 'd.get("status")')" = CANCELED ] \
  && row "내 예약 취소해 줘 → [확인]" ok "CANCELED" || row "내 예약 취소해 줘 → [확인]" no "$r"
# 멈춘 것만이 아니라 설정한 최대 횟수(기본 8, docs/설계.md)에서 멈췄는지 본다.
MAX_TOOL_CALLS=${MAX_TOOL_CALLS:-8}
r=$(chat "$OWNER" "무한 반복해"); [ "$(echo "$r" | j 'd.get("stoppedByLimit")')" = True ] && [ "$(echo "$r" | j 'd.get("toolCalls")')" = "$MAX_TOOL_CALLS" ] \
  && row "도구 호출 최대 횟수" ok "${MAX_TOOL_CALLS}번에서 멈춤" || row "도구 호출 최대 횟수" no "$r"
# 이번 실행에서 실제로 받은 값(학번·확인 토큰 2개·입실 번호)과 필드 이름(accessCode, "c_ 토큰 앞머리)을 글자 그대로(-F), 문자열 어디에 있든 찾는다. 다른 숫자에 우연히 걸리면 실패 쪽으로 틀리므로 안전하다.
# grep 종료 코드: 0 찾음, 1 없음, 2 이상 읽기 오류. 읽기 오류·요청 0건·값을 못 받은 경우는 통과로 치지 않는다.
gs=0; leaks=$(grep -cF -e "$P1" -e "$P2" -e "${tok1:-x}" -e "${tok2:-x}" -e "${code:-x}" -e "X-User-Id" -e "accessCode" -e "\"c_" "$SENT_LOG") || gs=$?
# 줄 수가 아니라, user 메시지가 든 정상 요청만 센다. 한 줄이라도 깨졌으면 0으로 보고 실패시킨다.
reqs=$(python3 -c '
import json,sys
n=0
for line in open(sys.argv[1],encoding="utf-8"):
    m=json.loads(line).get("messages")
    if not isinstance(m,list) or not any(isinstance(x,dict) and x.get("role")=="user" for x in m): sys.exit(1)
    n+=1
print(n)' "$SENT_LOG" 2>/dev/null) || reqs=0
if [ "$gs" -gt 1 ] || [ "${reqs:-0}" -eq 0 ]; then row "외부 AI로 나간 내용" no "로그를 읽지 못했거나 요청이 기록되지 않음"
elif [ -z "${tok1:-}" ] || [ -z "${tok2:-}" ] || [ -z "${code:-}" ]; then row "외부 AI로 나간 내용" no "확인 토큰·입실 번호를 받지 못해 검사할 값이 없음"
elif [ "${leaks:-0}" = 0 ]; then row "외부 AI로 나간 내용" ok "학번 2개·확인 토큰 2개·입실 번호 실제 값 0건 (${reqs}번 요청)"
else row "외부 AI로 나간 내용" no "$leaks줄에서 발견"; fi
echo; echo "통과 $pass, 실패 $fail"; [ "$fail" = 0 ]
