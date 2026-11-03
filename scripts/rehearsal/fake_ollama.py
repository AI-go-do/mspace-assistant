"""시연 리허설용 가짜 Ollama (POST /api/chat). API 키·GPU 없이 agent 반복을 끝까지 돌린다.

마지막 학생 말의 낱말(빈방·예약해·취소·입실 번호·수업 끝나고·무한)과 이미 받은 도구 결과를 보고
정해 둔 도구를 부른다. 진짜 모델의 정확도는 재지 않는다(#9에서 진짜 모델로 잰다).
받은 요청 본문은 전부 SENT_LOG(기본 ./sent.jsonl)에 남겨, 외부 AI로 무엇이 나가는지 검사한다.
표준 라이브러리만 쓴다. 실행: python3 -I fake_ollama.py [포트=18111]
"""
import json, os, sys, datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
LOG = os.environ.get("SENT_LOG", "sent.jsonl")
def call(name, args): return {"message": {"role": "assistant", "content": "", "tool_calls": [{"function": {"name": name, "arguments": args}}]}, "done": True}
def say(t): return {"message": {"role": "assistant", "content": t}, "done": True}
TOMORROW = (datetime.date.today() + datetime.timedelta(days=1)).isoformat()
def brain(msgs):
    users = [m for m in msgs if m["role"] == "user"]
    q = users[-1]["content"]
    idx = max(i for i, m in enumerate(msgs) if m["role"] == "user")
    tools = [m for m in msgs[idx:] if m["role"] == "tool"]
    done = {t["tool_name"]: t["content"] for t in tools}
    if "빈방" in q:
        if "find_available_rooms" not in done: return call("find_available_rooms", {"date": TOMORROW, "from": "14:00", "to": "16:00", "headcount": 3})
        return say("내일 14:00–16:00에 비어 있는 방을 찾았어요: " + done["find_available_rooms"][:120])
    if "예약해" in q:
        if "create_reservation" not in done: return call("create_reservation", {"roomId": 2, "startAt": TOMORROW + "T14:00", "endAt": TOMORROW + "T16:00", "headcount": 3})
        return say("확인 카드를 띄웠어요. [확인]을 누르고 함께 쓰는 2명의 학번을 넣어 주세요.")
    if "취소" in q or "입실 번호" in q:
        if "my_reservations" not in done: return call("my_reservations", {})
        rs = json.loads(done["my_reservations"]).get("reservations", [])
        mine = [r for r in rs if r.get("owner") and r.get("status") in ("BOOKED", "IN_USE")]
        if not mine: return say("끝나지 않은 내 예약이 없어요.")
        tool = "cancel_reservation" if "취소" in q else "show_access_code"
        if tool not in done: return call(tool, {"reservationId": mine[0]["id"]})
        return say("확인 카드를 띄웠어요." if tool == "cancel_reservation" else "입실 번호는 화면 카드에서 확인해 주세요.")
    if "수업 끝나고" in q: return say("수업이 몇 시에 끝나요? 끝나는 시각을 알려 주시면 그때부터 2시간 빈방을 찾을게요.")
    if "무한" in q: return call("my_reservations", {})  # 도구를 끝없이 부르는 모델 흉내
    return say("무엇을 도와드릴까요?")
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.headers.get("Content-Length"):
            raw = self.rfile.read(int(self.headers["Content-Length"]))
        else:  # Java HttpClient는 chunked로 보낸다
            raw = b""
            while True:
                size = int(self.rfile.readline().strip(), 16)
                if size == 0: self.rfile.readline(); break
                raw += self.rfile.read(size); self.rfile.readline()
        body = json.loads(raw)
        with open(LOG, "a") as f: f.write(json.dumps(body, ensure_ascii=False) + "\n")
        out = json.dumps(brain(body["messages"]), ensure_ascii=False).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(out))); self.end_headers(); self.wfile.write(out)
    def log_message(self, *a): pass
HTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 18111), H).serve_forever()
