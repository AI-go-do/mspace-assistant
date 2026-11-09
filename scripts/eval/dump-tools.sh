#!/usr/bin/env bash
# 진짜 mcp-server의 tools/list를 떠서 평가용 스냅숏(agent/src/test/resources/eval/tools.json)을 새로 써요.
# 도구 이름·설명·입력 스키마를 고친 PR에서 같이 돌려요. core는 필요 없어요(목록만 받음).
set -euo pipefail
cd "$(dirname "$0")/../.."
./mvnw -q -pl mcp-server -am -DskipTests package
PORT=18090
SERVER_PORT=$PORT CORE_BASE_URL=http://localhost:1 java -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar >/dev/null 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null' EXIT
for _ in $(seq 1 60); do curl -sf localhost:$PORT/actuator/health >/dev/null && break; sleep 1; done
H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
SID=$(curl -s -D - -o /dev/null "${H[@]}" localhost:$PORT/mcp \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"eval","version":"1"}}}' \
  | awk 'tolower($1)=="mcp-session-id:"{print $2}' | tr -d '\r')
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" localhost:$PORT/mcp -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
curl -s "${H[@]}" -H "Mcp-Session-Id: $SID" localhost:$PORT/mcp -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | python3 -I -c '
import json, re, sys
raw = sys.stdin.read()
m = re.search(r"^data:(.*)$", raw, re.M)
d = json.loads(m.group(1) if m else raw)
tools = sorted(({"name": t["name"], "description": t["description"], "inputSchema": t["inputSchema"]} for t in d["result"]["tools"]), key=lambda t: t["name"])
json.dump({"tools": tools}, open("agent/src/test/resources/eval/tools.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(len(tools), "개 도구를 썼어요")'
