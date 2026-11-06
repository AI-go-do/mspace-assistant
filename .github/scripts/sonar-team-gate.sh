#!/usr/bin/env bash
# 팀 규칙 품질 게이트 (TEAM.md 7장 "main은 새 코드 커버리지 80% 이상").
# 학교 Sonar 기본 게이트(Sonar way) 판정 대신, 방금 올린 분석의 새 코드 커버리지·중복만 읽어 판정한다.
# 필요한 환경변수: SONAR_TOKEN, SONAR_HOST_URL, SONAR_PROJECT, MIN_COVERAGE, MAX_DUPLICATION
# 토큰 값은 출력하지 않는다
set -euo pipefail

api() { curl -sf -m 30 -u "$SONAR_TOKEN:" "$SONAR_HOST_URL/api/$1"; }

# 1) 스캐너가 남긴 작업 id로 서버 처리가 끝나기를 기다린다 (최대 5분)
report=target/sonar/report-task.txt
[ -f "$report" ] || { echo "$report 가 없어요: Sonar 분석이 올라가지 않았어요"; exit 1; }
task_id=$(grep '^ceTaskId=' "$report" | cut -d= -f2-)
status=PENDING
for _ in $(seq 1 60); do
  status=$(api "ce/task?id=$task_id" | jq -r '.task.status')
  case "$status" in
    SUCCESS) break ;;
    FAILED|CANCELED) echo "Sonar 서버가 분석을 처리하지 못했어요: $status"; exit 1 ;;
  esac
  sleep 5
done
[ "$status" = SUCCESS ] || { echo "Sonar 분석 처리 시간이 5분을 넘었어요 (마지막 상태: $status)"; exit 1; }

# 2) 새 코드 값 읽기. 새 코드 지표는 period.value에 들어 있다. 덮을 새 줄이 없으면 커버리지 값이 없을 수 있다
measures=$(api "measures/component?component=$SONAR_PROJECT&metricKeys=new_coverage,new_duplicated_lines_density,new_lines_to_cover,new_violations")
val() { echo "$measures" | jq -r --arg k "$1" '[.component.measures[] | select(.metric == $k) | (.period.value // .value)][0] // empty'; }
coverage=$(val new_coverage)
duplication=$(val new_duplicated_lines_density)
to_cover=$(val new_lines_to_cover)
issues=$(val new_violations)

echo "새 코드 커버리지: ${coverage:-없음}% (기준 ${MIN_COVERAGE}% 이상, 덮을 새 줄 ${to_cover:-0}줄)"
echo "새 코드 중복: ${duplication:-0}% (기준 ${MAX_DUPLICATION}% 이하)"
echo "새 코드 이슈: ${issues:-0}건 (막지 않음, 대시보드에서 줄여 가기)"

fail=0
if [ -n "$coverage" ] && awk -v a="$coverage" -v b="$MIN_COVERAGE" 'BEGIN{exit !(a < b)}'; then
  echo "실패: 새 코드 커버리지가 ${MIN_COVERAGE}%보다 낮아요"; fail=1
fi
if [ -z "$coverage" ] && [ "${to_cover:-0}" != "0" ]; then
  echo "실패: 덮을 새 줄이 있는데 커버리지 값이 없어요 (JaCoCo 보고서가 안 올라갔을 수 있음)"; fail=1
fi
if [ -n "$duplication" ] && awk -v a="$duplication" -v b="$MAX_DUPLICATION" 'BEGIN{exit !(a > b)}'; then
  echo "실패: 새 코드 중복이 ${MAX_DUPLICATION}%보다 높아요"; fail=1
fi
[ "$fail" = 0 ] && echo "팀 규칙 품질 게이트 통과"
exit "$fail"
