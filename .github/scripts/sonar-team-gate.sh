#!/usr/bin/env bash
# 팀 규칙 품질 게이트 (TEAM.md 7장 "main은 새 코드 커버리지 80% 이상").
# 학교 Sonar 기본 게이트(Sonar way) 판정 대신, 방금 올린 분석의 새 코드 값을 읽어 판정한다.
# 막는 것: 커버리지 미달, 중복 초과, 새 버그(신뢰성 등급 A 아님), 새 취약점(보안 등급 A 아님)
# 막지 않는 것: 코드 스멜(스타일·복잡도), 보안 핫스팟 검토 — 대시보드에 남기고 시연 뒤에 줄여 간다
# 필요한 환경변수: SONAR_TOKEN, SONAR_HOST_URL, SONAR_PROJECT, MIN_COVERAGE, MAX_DUPLICATION
# 토큰 값은 출력하지 않는다
set -euo pipefail

api() { curl -sf -m 30 -u "$SONAR_TOKEN:" "$SONAR_HOST_URL/api/$1"; }

# 1) 스캐너가 남긴 작업 id로 서버 처리가 끝나기를 기다린다 (최대 5분)
report=target/sonar/report-task.txt
[ -f "$report" ] || { echo "$report 가 없어요: Sonar 분석이 올라가지 않았어요"; exit 1; }
task_id=$(grep '^ceTaskId=' "$report" | cut -d= -f2- || true)
[ -n "$task_id" ] || { echo "$report 에 ceTaskId가 없어요"; exit 1; }
status=PENDING
# 요청이 매번 시간 초과돼도 전체 5분을 넘지 않게, 횟수가 아니라 경과 시간으로 끝낸다
deadline=$((SECONDS + 300))
while [ "$SECONDS" -lt "$deadline" ]; do
  # 한 번 못 읽어도(네트워크 순간 끊김) 멈추지 않고 다시 묻는다
  status=$(api "ce/task?id=$task_id" | jq -r '.task.status' || echo PENDING)
  case "$status" in
    SUCCESS) break ;;
    FAILED|CANCELED) echo "Sonar 서버가 분석을 처리하지 못했어요: $status"; exit 1 ;;
  esac
  sleep 5
done
[ "$status" = SUCCESS ] || { echo "Sonar 분석 처리 시간이 5분을 넘었어요 (마지막 상태: $status)"; exit 1; }
analysis_id=$(api "ce/task?id=$task_id" | jq -r '.task.analysisId // empty')
[ -n "$analysis_id" ] || { echo "분석 id를 읽지 못했어요"; exit 1; }

# Community 서버는 브랜치·PR을 나눠 담지 못하고 프로젝트 하나에 덮어쓴다. 그래서 값을 읽기 전과 뒤에
# 프로젝트의 마지막 분석이 이 분석인지 확인해, 다른 분석(develop push 등)의 값으로 판정하지 않게 한다
# 마지막 분석 id는 분석 목록(project_analyses)에서 읽고, 권한이 없으면(403) 프로젝트 작업 현황(ce/component,
# 프로젝트 보기 권한으로 열림)의 마지막 완료 작업에서 읽는다 (11-06 #81: 분석 목록이 막혀 매번 실패)
# 둘 다 못 읽을 때만 확인을 건너뛰고 경고를 남긴다
latest() {
  local k
  k=$(api "project_analyses/search?project=$SONAR_PROJECT&ps=1" | jq -r '.analyses[0].key // empty' || true)
  [ -n "$k" ] || k=$(api "ce/component?component=$SONAR_PROJECT" | jq -r '.current.analysisId // empty' || true)
  echo "$k"
}
check_mine() {
  local now; now=$(latest)
  if [ -z "$now" ]; then
    echo "경고: 마지막 분석 id를 읽지 못해(권한 없음 등) 겹친 분석 확인은 건너뛰어요"; return 0
  fi
  [ "$now" = "$analysis_id" ] && return 0
  echo "그 사이 다른 분석(develop push 등)이 끝나 프로젝트 값이 바뀌었어요 (이 분석 $analysis_id, 마지막 분석 $now). 이 build를 다시 실행해 주세요"
  exit 1
}
check_mine

# 2) 새 코드 값 읽기. 새 코드 지표는 period.value에 들어 있다. 덮을 새 줄이 없으면 커버리지 값이 없을 수 있다
# 지표는 하나씩 읽는다. 서버에 없는 지표 키가 하나라도 있으면 한꺼번에 읽기가 404로 통째로 실패한다
# (11-06 #81: "Sonar 지표를 읽지 못했어요"). 못 읽은 지표는 상태 코드와 응답 앞부분을 찍고 빈 값으로 둔다
val() {
  local out code
  out=$(curl -sS -m 30 -u "$SONAR_TOKEN:" -w '\n%{http_code}' \
        "$SONAR_HOST_URL/api/measures/component?component=$SONAR_PROJECT&metricKeys=$1" 2>&1) || true
  code=$(echo "$out" | tail -n1)
  if [ "$code" != "200" ]; then
    echo "참고: 지표 $1 을(를) 읽지 못했어요 (상태 $code: $(echo "$out" | sed '$d' | head -c 200))" >&2
    return 0
  fi
  echo "$out" | sed '$d' | jq -r --arg k "$1" '[.component.measures[]? | select(.metric == $k) | (.period.value // .value)][0] // empty'
}
# 건수 지표(new_bugs·new_vulnerabilities)가 없으면 새 코드 이슈 목록에서 유형 BUG·VULNERABILITY 수를 센다
# 새 품질 모델(MQR)의 신뢰성·보안 이슈 수는 코드 스멜도 섞여 있어 쓰지 않는다 (코드 스멜은 막지 않는 팀 규칙)
count_type() {
  local out code
  out=$(curl -sS -m 30 -u "$SONAR_TOKEN:" -w '\n%{http_code}' \
        "$SONAR_HOST_URL/api/issues/search?componentKeys=$SONAR_PROJECT&inNewCodePeriod=true&resolved=false&types=$1&ps=1" 2>&1) || true
  code=$(echo "$out" | tail -n1)
  if [ "$code" != "200" ]; then
    echo "참고: 유형 $1 이슈 수를 읽지 못했어요 (상태 $code: $(echo "$out" | sed '$d' | head -c 200))" >&2
    return 0
  fi
  echo "$out" | sed '$d' | jq -r '.total // empty'
}
coverage=$(val new_coverage)
duplication=$(val new_duplicated_lines_density)
to_cover=$(val new_lines_to_cover)
issues=$(val new_violations)
bugs=$(val new_bugs)
[ -n "$bugs" ] || bugs=$(count_type BUG)
vulns=$(val new_vulnerabilities)
[ -n "$vulns" ] || vulns=$(count_type VULNERABILITY)
rel=$(val new_reliability_rating)   # 1.0=A … 5.0=E
sec=$(val new_security_rating)
check_mine

echo "새 코드 커버리지: ${coverage:-없음}% (기준 ${MIN_COVERAGE}% 이상, 덮을 새 줄 ${to_cover:-0}줄)"
echo "새 코드 중복: ${duplication:-0}% (기준 ${MAX_DUPLICATION}% 이하)"
echo "새 버그: ${bugs:-0}건, 새 취약점: ${vulns:-0}건 (있으면 실패)"
echo "새 코드 이슈 전체: ${issues:-0}건 (코드 스멜은 막지 않음, 대시보드에서 줄여 가기)"

fail=0
if [ -n "$coverage" ] && awk -v a="$coverage" -v b="$MIN_COVERAGE" 'BEGIN{exit !(a < b)}'; then
  echo "실패: 새 코드 커버리지가 ${MIN_COVERAGE}%보다 낮아요"; fail=1
fi
# 지표가 빠진 채로 통과하지 않게: 덮을 새 줄 수는 늘 있어야 하고, 새 줄이 있으면 중복 값도 있어야 한다
if [ -z "$to_cover" ]; then
  echo "실패: 새 코드 줄 수(new_lines_to_cover)를 읽지 못했어요"; fail=1
elif [ "$to_cover" != "0" ] && [ -z "$duplication" ]; then
  echo "실패: 새 코드 중복 값을 읽지 못했어요"; fail=1
fi
if [ -z "$coverage" ] && [ "${to_cover:-0}" != "0" ]; then
  echo "실패: 덮을 새 줄이 있는데 커버리지 값이 없어요 (JaCoCo 보고서가 안 올라갔을 수 있음)"; fail=1
fi
if [ -n "$duplication" ] && awk -v a="$duplication" -v b="$MAX_DUPLICATION" 'BEGIN{exit !(a > b)}'; then
  echo "실패: 새 코드 중복이 ${MAX_DUPLICATION}%보다 높아요"; fail=1
fi
# 등급 값이 비어 있어도 건수로 한 번 더 본다. 둘 다 못 읽으면 0으로 치지 않고 실패한다
if [ -z "$rel" ] && [ -z "$bugs" ]; then
  echo "실패: 새 버그 건수와 신뢰성 등급을 모두 읽지 못했어요"; fail=1
fi
if [ -z "$sec" ] && [ -z "$vulns" ]; then
  echo "실패: 새 취약점 건수와 보안 등급을 모두 읽지 못했어요"; fail=1
fi
if { [ -n "$rel" ] && awk -v a="$rel" 'BEGIN{exit !(a > 1)}'; } || awk -v a="${bugs:-0}" 'BEGIN{exit !(a > 0)}'; then
  echo "실패: 새 코드에 버그가 있어요 (신뢰성 등급 A 아님). 대시보드 Issues에서 유형 Bug를 보세요"; fail=1
fi
if { [ -n "$sec" ] && awk -v a="$sec" 'BEGIN{exit !(a > 1)}'; } || awk -v a="${vulns:-0}" 'BEGIN{exit !(a > 0)}'; then
  echo "실패: 새 코드에 취약점이 있어요 (보안 등급 A 아님). 대시보드 Issues에서 유형 Vulnerability를 보세요"; fail=1
fi
[ "$fail" = 0 ] && echo "팀 규칙 품질 게이트 통과"
exit "$fail"
