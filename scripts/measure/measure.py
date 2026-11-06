#!/usr/bin/env python3
"""성공률 측정(#9): 질문 세트를 agent에 보내고 '맞는 도구를 골랐는지'로 판정해 표로 남긴다.

사용:
  AGENT=https://dev-mspace.java21.net TOKEN=<로그인 토큰> python3 -I scripts/measure/measure.py --runs 3
  AGENT=http://localhost:8091 USER_ID=1 python3 -I scripts/measure/measure.py            # local 프로필(X-User-Id)

- 토큰은 환경 변수로만 받고 출력·파일에 적지 않는다. 결과 파일에는 질문·판정·도구 이름·걸린 시간·답 앞 80자(가린 것)만 남긴다
- 질문마다 먼저 DELETE /api/agent/chat/history로 대화를 비워 앞 질문의 맥락이 섞이지 않게 한다
- 쓰기 도구는 확인 카드까지만. [확인]은 누르지 않으니 예약이 생기지 않는다
- 표준 라이브러리만 쓴다 (python3 -I로 실행)
"""
import argparse
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def headers():
    h = {"Content-Type": "application/json"}
    token = os.environ.get("TOKEN")
    user = os.environ.get("USER_ID")
    if token:
        h["Authorization"] = "Bearer " + token
    elif user:
        h["X-User-Id"] = user
    else:
        sys.exit("TOKEN(Bearer) 또는 USER_ID(local 프로필) 환경 변수가 필요해요. 토큰은 채팅·파일에 붙이지 마세요.")
    return h


def call(method, url, body=None, timeout=15):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(url, data=data, method=method, headers=headers())
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            raw = res.read().decode("utf-8", "replace")
            return res.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"code": "HTTP_" + str(e.code), "message": raw[:200]}
    except (urllib.error.URLError, TimeoutError) as e:
        return 0, {"code": "CONNECT_FAILED", "message": str(e)[:200]}


def reset_history(agent):
    """DELETE /api/agent/chat/history. 직전 작업 정리 중이면 409 CHAT_IN_PROGRESS가 날 수 있어 잠깐 기다려 다시 시도한다"""
    for attempt in range(5):
        status, body = call("DELETE", agent + "/api/agent/chat/history")
        if status in (204, 200):
            return
        if status == 409:
            time.sleep(1 + attempt)
            continue
        sys.exit(f"대화 기록을 지우지 못했어요 (status {status} {body.get('code')}). 측정을 멈춰요")
    sys.exit("대화 기록을 지우지 못했어요 (계속 409 CHAT_IN_PROGRESS). 측정을 멈춰요")


def chat(agent, text, poll_limit):
    """접수(202) → 폴링. 결과(result dict)와 걸린 시간(초)을 준다. 실패면 {"code": ...}"""
    started = time.monotonic()
    status, body = call("POST", agent + "/api/agent/chat", {"message": text, "history": []})
    if status != 202 or "jobId" not in body:
        return body if body else {"code": "ACCEPT_FAILED"}, time.monotonic() - started
    job = body["jobId"]
    deadline = time.monotonic() + poll_limit
    while time.monotonic() < deadline:
        time.sleep(max(0.3, body.get("pollAfterMs", 1000) / 1000))
        status, body = call("GET", agent + "/api/agent/chat/" + job)
        if status != 200:
            return body, time.monotonic() - started
        if body.get("status") == "DONE":
            return body.get("result", {}), time.monotonic() - started
        if body.get("status") not in ("PENDING", "RUNNING"):
            return body, time.monotonic() - started
    return {"code": "POLL_TIMEOUT"}, time.monotonic() - started


def judge_one(exp, result):
    """expect 항목 하나를 검사한다. 모든 조건이 맞아야 True"""
    tools = result.get("tools") or []
    confirms = result.get("confirmations") or []
    reply = result.get("reply") or ""
    if "tools_any" in exp and not any(t in tools for t in exp["tools_any"]):
        return False
    if "tools_all" in exp and not all(t in tools for t in exp["tools_all"]):
        return False
    if "tools_none" in exp and any(t in tools for t in exp["tools_none"]):
        return False
    if exp.get("no_tool") and tools:
        return False
    if "tools_count" in exp and any(tools.count(t) != n for t, n in exp["tools_count"].items()):
        return False
    if "first_tool" in exp and (not tools or tools[0] != exp["first_tool"]):
        return False
    if exp.get("not_stopped") and result.get("stoppedByLimit"):
        return False
    if "confirm_tool" in exp and not any(c.get("tool") == exp["confirm_tool"] for c in confirms):
        return False
    if "needs_participants" in exp and not any(c.get("needsParticipants") == exp["needs_participants"] for c in confirms):
        return False
    if "reply_any" in exp and not any(k in reply for k in exp["reply_any"]):
        return False
    return True


def judge(question, result):
    if "reply" not in result:  # 오류 본문({code}), 실패 작업({status: FAILED, error: {code}}), 빈 결과 모두 실패
        code = result.get("code") or (result.get("error") or {}).get("code") or result.get("status") or "응답 없음"
        return False, "오류 " + str(code)
    ok = any(judge_one(e, result) for e in question["expect"])
    why = "도구 " + (",".join(result.get("tools") or []) or "없음")
    if result.get("confirmations"):
        why += " / 카드 " + ",".join(c.get("tool", "?") for c in result["confirmations"])
    if result.get("stoppedByLimit"):
        why += " / 최대 횟수에서 멈춤"
    return ok, why


# 결과 파일에 남는 글(질문·답)에서 가리는 것: 6자리 이상 숫자(학번·입실 번호), 구분자 있는 번호(010-1234-5678, 2020 1234),
# 한글 이름 모양(2~4자 + 님/학생/씨). 기본 질문 세트에는 실제 정보가 없지만 --questions로 다른 파일을 쓸 수 있어서 모두 거친다
MASKS = [(re.compile(r"\d{6,}"), "[숫자]"),
         (re.compile(r"\d{2,4}[-\s]\d{3,4}[-\s]\d{4}"), "[번호]"),
         (re.compile(r"\d{4}[-\s]\d{4}"), "[번호]"),
         (re.compile(r"[가-힣]{2,4}\s?(님|학생|씨)"), "[이름]")]


def mask(text):
    for pattern, repl in MASKS:
        text = pattern.sub(repl, text)
    return text


def snippet(result):
    text = (result.get("reply") or result.get("message") or "").replace("\n", " ")
    return mask(text)[:80]


def check_agent_url(agent):
    """토큰은 https로만 보낸다. http는 내 컴퓨터(localhost)일 때만"""
    parsed = urllib.parse.urlparse(agent)
    if parsed.scheme == "https":
        return
    if parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1", "::1"):
        return
    sys.exit(f"AGENT는 https:// 또는 http://localhost 만 돼요: {agent} (토큰이 평문으로 나가요)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=1, help="질문 세트를 몇 번 돌릴지 (기본 1)")
    ap.add_argument("--questions", default=os.path.join(HERE, "questions.json"))
    ap.add_argument("--out", default=None, help="결과 Markdown 경로 (기본 scripts/measure/results/<날짜>.md)")
    ap.add_argument("--poll-limit", type=int, default=90)
    ap.add_argument("--only", default=None, help="쉼표로 나눈 질문 id만 (예: Q01,Q05)")
    args = ap.parse_args()

    agent = os.environ.get("AGENT", "http://localhost:8091").rstrip("/")
    model = os.environ.get("MODEL_LABEL", "(모델 이름을 MODEL_LABEL로 넣어요)")
    with open(args.questions, encoding="utf-8") as f:
        questions = json.load(f)["questions"]
    if args.only:
        keep = set(args.only.split(","))
        questions = [q for q in questions if q["id"] in keep]
        if not questions:
            sys.exit(f"--only에 맞는 질문이 없어요: {args.only}")
    check_agent_url(agent)

    status, _ = call("GET", agent + "/actuator/health")
    if status != 200:
        sys.exit(f"agent에 닿지 못했어요: {agent} (status {status})")

    rows = []
    for run in range(1, args.runs + 1):
        for q in questions:
            reset_history(agent)  # 새 대화. 앞 질문의 기억이 남으면 독립 측정이 아니라서 실패하면 멈춘다
            result, secs = chat(agent, q["text"], args.poll_limit)
            ok, why = judge(q, result)
            rows.append({"run": run, "id": q["id"], "group": q["group"], "text": q["text"], "ok": ok, "why": why,
                         "seconds": round(secs, 1), "toolCalls": result.get("toolCalls"), "snippet": snippet(result)})
            print(f"[{run}/{args.runs}] {q['id']} {'통과' if ok else '실패'} {secs:.1f}s {why}", flush=True)

    total = len(rows)
    passed = sum(r["ok"] for r in rows)
    by_q = {}
    for r in rows:
        by_q.setdefault(r["id"], []).append(r)
    today = dt.datetime.now(dt.timezone(dt.timedelta(hours=9))).strftime("%Y-%m-%d %H:%M")
    out = args.out or os.path.join(HERE, "results", dt.datetime.now().strftime("%Y-%m-%d-%H%M") + ".md")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    lines = [f"# 성공률 측정 {today} (+09:00)", "",
             f"- 모델: {model}", f"- agent: {agent}", f"- 질문 {len(questions)}개 × {args.runs}회 = {total}건",
             f"- **성공률 {passed}/{total} = {100 * passed / total:.0f}%**",
             f"- 평균 응답 {sum(r['seconds'] for r in rows) / total:.1f}초, 평균 도구 호출 {sum(r['toolCalls'] or 0 for r in rows) / total:.1f}번",
             "", "| 질문 | 묶음 | 통과/시도 | 평균 초 | 판정 근거(회차별, 다르면 //로 이어서) |", "|---|---|---|---|---|"]
    for qid, rs in by_q.items():
        q = next(x for x in questions if x["id"] == qid)
        lines.append(f"| {qid} {mask(q['text'])} | {q['group']} | {sum(r['ok'] for r in rs)}/{len(rs)} | "
                     f"{sum(r['seconds'] for r in rs) / len(rs):.1f} | {' // '.join(dict.fromkeys(r['why'] for r in rs))} |")  # 회차별 도구 목록이 다르면 모두 남김
    fails = [r for r in rows if not r["ok"]]
    if fails:
        lines += ["", "## 실패 상세 (답 앞 80자. 6자리 이상 숫자·전화번호 모양·이름 모양은 가림)", "", "| 회차 | 질문 | 근거 | 답 |", "|---|---|---|---|"]
        lines += [f"| {r['run']} | {r['id']} | {r['why']} | {r['snippet']} |" for r in fails]
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"\n성공률 {passed}/{total} = {100 * passed / total:.0f}% → {out}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
