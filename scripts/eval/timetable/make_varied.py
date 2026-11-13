"""여러 모양의 합성 시간표 만들기 (TS-19). tt01 한 장에만 맞춘 픽셀 재기가 다른 캡처에서도 맞는지 보려고 그린다.

python3 scripts/eval/timetable/make_varied.py
- 크기·확대·잘림·다크 모드·옅은 색·토요일·시작 시각(8시/9시)·눈금 글자 위치(줄 아래/칸 가운데/줄 위)·30분 선·JPEG 압축을 섞는다
- 과목·강의실·시각은 모두 지어낸 값. 실제 사람 시간표는 레포에 두지 않는다
- 사진마다 정답(.answer.json)과 '흉내 낸 모델 답'(.sim-model-answer.json)을 같이 만든다.
  흉내 낸 답은 11/12 실제 모델에서 본 실수(끝을 30~60분 더 내려 읽기, 시작 30분 늦게, 위치 8~25/1000 흔들림)를 따라 하고,
  일부 사진에서는 눈금을 12시간으로 적거나(1, 2, 3 …) 눈금 하나를 빼먹거나 한 시간 틀린 눈금을 섞는다
- 진짜 모델 점수가 아니라 '모델이 이 정도 틀릴 때 코드가 고치는지'를 키 없이 재는 용도다. 진짜 점수는 TimetableEvalRunTest
- 글꼴: WenQuanYi Zen Hei(리눅스 fonts-wqy-zenhei)
"""
import io, json, random, sys
from dataclasses import dataclass, field
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parents[3] / "agent/src/test/resources/eval/timetable-varied"
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
DAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT"]
DAY_KO = ["월", "화", "수", "목", "금", "토"]
NAMES = ["자료구조", "운영체제", "확률과 통계", "컴퓨터네트워크", "캡스톤디자인", "데이터베이스", "영어회화", "선형대수",
         "알고리즘", "웹프로그래밍", "근로", "동아리", "인공지능개론", "이산수학", "글쓰기", "체육"]
VIVID = [(110, 160, 235), (120, 200, 150), (245, 170, 100), (165, 135, 225), (238, 128, 118), (110, 205, 200),
         (240, 150, 190), (232, 196, 96)]
PASTEL = [(214, 228, 250), (214, 240, 222), (252, 232, 210), (232, 222, 248), (250, 220, 218), (212, 240, 238)]
DARK = [(63, 94, 150), (58, 120, 88), (150, 98, 52), (98, 78, 140), (140, 64, 60), (52, 118, 116)]


@dataclass
class Case:
    id: str
    note: str
    seed: int
    width: int = 750
    hour_px: int = 80
    first: int = 9
    last: int = 21
    days: int = 5
    theme: str = "light"            # light | dark
    palette: str = "vivid"          # vivid | pastel | dark
    text: str = "white"             # 칸 안 글자 색: white | dark
    label: str = "below"            # below(에브리타임: 줄 바로 아래) | center(칸 가운데) | online(줄 위에 걸침)
    twelve: bool = True             # 눈금을 1, 2, 3 …(12시간)으로 그림
    half: str = "none"              # 30분 선: none | dashed | solid
    header: int = 150               # 첫 정각 줄 위 여백
    crop: tuple = (0, 0, 0, 0)      # 그린 뒤 자를 픽셀 (왼, 위, 오른, 아래)
    scale: float = 1.0              # 그린 뒤 확대·축소
    jpeg: int = 0                   # 0이면 PNG
    fifty: bool = False             # 50분 수업(교시) 위주
    sim: dict = field(default_factory=dict)  # 흉내 낸 모델 답의 실수 종류


CASES = [
    Case("tv01-phone-hd", "폰 고해상도(1080 너비), 한 시간 120픽셀", 11, width=1080, hour_px=120, header=210),
    Case("tv02-sat-8am", "8시 시작 + 토요일 열, 눈금이 칸 가운데", 12, first=8, last=20, days=6, label="center"),
    Case("tv03-dark", "다크 모드(어두운 바탕·어두운 선·어두운 칸 색)", 13, theme="dark", palette="dark"),
    Case("tv04-pastel", "옅은 색 칸 + 검은 글자(채도가 낮음)", 14, palette="pastel", text="dark"),
    Case("tv05-crop-top", "위가 잘린 캡처(머리줄·9시 눈금 없음, 9시 반부터 보임)", 15, crop=(0, 190, 0, 0)),
    Case("tv06-lowres-jpeg", "작은 그림(한 시간 34픽셀) + JPEG 60", 16, width=360, hour_px=34, header=70, jpeg=60),
    Case("tv07-online-solid-half", "눈금이 줄 위에 걸침 + 30분 실선(옅게) + 24시간 눈금", 17, label="online", half="solid", twelve=False),
    Case("tv08-fifty-min", "50분 수업(교시) 위주, 9시 시작", 18, fifty=True),
    Case("tv09-tablet-sat", "가로로 넓은 태블릿(1600 너비), 토요일, 한 시간 60픽셀", 19, width=1600, hour_px=60, days=6, header=120),
    Case("tv10-dark-center-12h", "다크 + 눈금 칸 가운데 + 모델이 오후 눈금을 1, 2, 3으로 적음", 20, theme="dark", palette="dark",
         label="center", sim={"raw12": True}),
    Case("tv11-shrunk-dashed", "60%로 줄인 캡처(흐림) + 30분 점선 + 왼쪽 일부 잘림", 21, half="dashed", scale=0.6, crop=(20, 60, 0, 0)),
    Case("tv12-zoom-crop-bottom", "1.5배 확대 + 아래 잘림 + 모델이 눈금 하나 빼먹고 하나 틀림", 22, scale=1.5, crop=(0, 0, 0, 380),
         sim={"drop_label": True, "wrong_label": True}),
]


def schedule(case, rnd):
    """요일마다 겹치지 않는 수업 1~3개. 시작은 30분 칸(50분 수업이면 정각), 일부는 바로 붙임"""
    out = []
    for di in range(case.days):
        t = case.first * 60 + rnd.choice([0, 30, 60, 90])
        for _ in range(rnd.randint(1, 3)):
            dur = rnd.choice([50, 50, 110]) if case.fifty else rnd.choice([60, 90, 90, 120, 180])
            if case.fifty:
                t = (t + 59) // 60 * 60
            if t + dur > (case.last - 1) * 60:
                break
            out.append((DAYS[di], t, t + dur, rnd.choice(NAMES)))
            t += dur + rnd.choice([0, 0, 30, 60, 90])
    return out


def hhmm(m):
    return f"{m // 60:02d}:{m % 60:02d}"


def render(case):
    rnd = random.Random(case.seed)
    classes = schedule(case, rnd)
    left = max(40, case.width // 15)
    col = (case.width - left - 16) // case.days
    top = case.header
    height = top + (case.last - case.first) * case.hour_px + 60
    dark = case.theme == "dark"
    bg = (28, 28, 30) if dark else (255, 255, 255)
    grid = (58, 58, 62) if dark else (232, 232, 232)
    halfc = (44, 44, 48) if dark else (242, 242, 242)
    fg = (230, 230, 230) if dark else (20, 20, 20)
    muted = (150, 150, 150) if dark else (120, 120, 120)
    img = Image.new("RGB", (case.width, height), bg)
    d = ImageDraw.Draw(img)
    big = ImageFont.truetype(FONT, max(16, case.hour_px * 2 // 5))
    mid = ImageFont.truetype(FONT, max(10, case.hour_px // 4))
    small = ImageFont.truetype(FONT, max(9, case.hour_px * 17 // 80))

    def y_of(m):
        return top + (m / 60 - case.first) * case.hour_px

    d.text((left // 2, top // 6), "2026년 2학기", fill=(220, 60, 60), font=mid)
    d.text((left // 2, top // 6 + case.hour_px // 3), "시간표", fill=fg, font=big)
    d.line([(12, top - 40), (case.width - 12, top - 40)], fill=grid)
    for i in range(case.days):
        x = left + i * col
        d.line([(x, top - 40), (x, y_of(case.last * 60))], fill=grid)
        d.text((x + col // 2 - 9, top - 34), DAY_KO[i], fill=muted, font=mid)
    for h in range(case.first, case.last + 1):
        y = y_of(h * 60)
        x_start = left if case.label == "online" else 12
        d.line([(x_start, y), (case.width - 12, y)], fill=grid)
        if case.half != "none" and h < case.last:
            yh = y + case.hour_px / 2
            if case.half == "solid":
                d.line([(left, yh), (case.width - 12, yh)], fill=halfc)
            else:
                for x in range(left, case.width - 12, 8):
                    d.line([(x, yh), (x + 3, yh)], fill=grid)
        if h < case.last:
            label = str(h if (h <= 12 or not case.twelve) else h - 12)
            fs = mid.size
            ly = {"below": y + 4, "center": y + case.hour_px / 2 - fs / 2, "online": y - fs / 2}[case.label]
            d.text((left // 2 - fs // 3, ly), label, fill=muted, font=mid)
    palette = {"vivid": VIVID, "pastel": PASTEL, "dark": DARK}[case.palette]
    tcol = (35, 35, 35) if case.text == "dark" else (255, 255, 255)
    boxes = []
    for i, (day, s, e, name) in enumerate(classes):
        x0 = left + DAYS.index(day) * col + 1
        x1 = x0 + col - 2
        y0, y1 = round(y_of(s)) + 1, round(y_of(e)) - 1
        color = palette[(sum(map(ord, name)) + case.seed) % len(palette)]
        d.rounded_rectangle([x0, y0, x1, y1], radius=max(2, case.hour_px // 20), fill=color)
        line, ty = "", y0 + 5
        for ch in name:
            if d.textlength(line + ch, font=small) > col - 12:
                d.text((x0 + 5, ty), line, fill=tcol, font=small)
                ty += small.size + 3
                line = ""
            line += ch
        if ty + small.size < y1:
            d.text((x0 + 5, ty), line, fill=tcol, font=small)
        boxes.append((x0, y0, x1, y1 + 1))
    labels = []
    for h in range(case.first, case.last):
        fs = mid.size
        y = y_of(h * 60)
        ly = {"below": y + 4, "center": y + case.hour_px / 2 - fs / 2, "online": y - fs / 2}[case.label]
        labels.append((h, ly, ly + fs, left // 2 - fs // 3, left // 2 + fs))

    # 자르기 → 크기 바꾸기 (좌표도 같이 옮긴다)
    cl, ct, cr, cb = case.crop
    img = img.crop((cl, ct, img.width - cr, img.height - cb))
    W0, H0 = img.size
    if case.scale != 1.0:
        img = img.resize((round(W0 * case.scale), round(H0 * case.scale)), Image.LANCZOS)
    W, H = img.size

    def tx(x):
        return (x - cl) / W0 * 1000

    def ty_(y):
        return (y - ct) / H0 * 1000

    visible = []
    for c, b in zip(classes, boxes):
        if b[1] >= ct and b[3] <= H0 + ct and b[0] >= cl:      # 칸 전체가 보이는 것만 정답에 넣는다
            visible.append((c, b))
    return img, classes, visible, labels, (tx, ty_), rnd


def sim_answer(case, visible, labels, tx, ty_, rnd, H0):
    def n(lo=8, hi=25):
        return rnd.choice([-1, 1]) * rnd.randint(lo, hi)

    hours = []
    for (h, y0, y1, x0, x1) in labels:
        if ty_(y0) < 0 or ty_(y1) > 1000:
            continue
        hour = h - 12 if case.sim.get("raw12") and h > 12 else h
        hours.append({"hour": hour, "box_2d": [round(ty_(y0) + rnd.randint(-6, 6)), round(tx(x0)),
                                               round(ty_(y1) + rnd.randint(-6, 6)), round(tx(x1))]})
    if case.sim.get("drop_label") and len(hours) > 4:
        hours.pop(len(hours) // 2)
    if case.sim.get("wrong_label") and len(hours) > 4:
        hours[1]["hour"] += 1
    classes = []
    for (day, s, e, name), (x0, y0, x1, y1) in visible:
        kind = rnd.random()
        ms, me = s, e
        if kind < 0.25:
            me = e + 30
        elif kind < 0.4:
            me = e + 60
        elif kind < 0.55:
            ms = s + 30
        elif kind < 0.65:
            me = (e + 29) // 60 * 60 + 60   # 50분 수업을 다음 정각까지 뭉갬
        classes.append({"dayOfWeek": day, "startTime": hhmm(ms), "endTime": hhmm(me), "name": name,
                        "box_2d": [round(ty_(y0) + n()), round(tx(x0) + rnd.randint(-8, 8)),
                                   round(ty_(y1) + n()), round(tx(x1) + rnd.randint(-8, 8))]})
    for c in classes:
        c["box_2d"] = [min(1000, max(0, v)) for v in c["box_2d"]]
    return {"hours": hours, "classes": classes}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for case in CASES:
        img, classes, visible, labels, (tx, ty_), rnd = render(case)
        ext = "jpg" if case.jpeg else "png"
        buf = io.BytesIO()
        if case.jpeg:
            img.save(buf, "JPEG", quality=case.jpeg)
        else:
            img.save(buf, "PNG", optimize=True)
        (OUT / f"{case.id}.{ext}").write_bytes(buf.getvalue())
        answer = {"source": "합성 그림(scripts/eval/timetable/make_varied.py). 과목·시각은 모두 지어낸 값",
                  "note": case.note,
                  "classes": [{"dayOfWeek": c[0], "startTime": hhmm(c[1]), "endTime": hhmm(c[2]), "name": c[3]} for c, _ in visible]}
        (OUT / f"{case.id}.answer.json").write_text(json.dumps(answer, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        sim = sim_answer(case, visible, labels, tx, ty_, rnd, img.height)
        (OUT / f"{case.id}.sim-model-answer.json").write_text(json.dumps(sim, ensure_ascii=False) + "\n", encoding="utf-8")
        print(case.id, img.size, len(visible), "칸", len(buf.getvalue()) // 1024, "KB")


if __name__ == "__main__":
    sys.exit(main())
