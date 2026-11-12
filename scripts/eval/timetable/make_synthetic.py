"""합성 시간표 그림 만들기 (TS-18). 실제 사람 시간표를 레포에 두지 않으려고 에브리타임 모양을 흉내 낸 가짜 시간표를 그린다.

python3 scripts/eval/timetable/make_synthetic.py
- 과목·강의실·시각은 모두 지어낸 값. 정답 JSON과 '손 만든 모델 답'(위치를 일부러 흔들고, 11/12에 실제로 본 실수 모양을 흉내 낸 시각)도 같이 만든다
- 글꼴: WenQuanYi Zen Hei(리눅스 fonts-wqy-zenhei). 다른 글꼴이면 픽셀이 조금 달라져도 테스트 결과는 같아야 한다
"""
import json, random, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parents[3] / "agent/src/test/resources/eval/timetable"
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"

W, H = 750, 1180
TOP, LEFT, HOUR = 150, 52, 80        # 9시 줄 y, 시간 눈금 칸 너비, 한 시간 픽셀
FIRST, LAST = 9, 21                   # 9시~21시
DAYS = ["MON", "TUE", "WED", "THU", "FRI"]
DAY_KO = ["월", "화", "수", "목", "금"]
COL = (W - LEFT - 16) // 5

C = {"blue": (110, 160, 235), "green": (120, 200, 150), "orange": (245, 170, 100), "purple": (165, 135, 225),
     "red": (238, 128, 118), "teal": (110, 205, 200), "pink": (240, 150, 190), "yellow": (232, 196, 96), "slate": (130, 150, 180)}
# (요일, 시작, 끝, 과목, 강의실, 색) 모두 지어낸 값
CLASSES = [
    ("MON", "09:00", "10:30", "자료구조", "공학관 301", "blue"),
    ("MON", "10:30", "12:00", "자료구조 실습", "공학관 301", "blue"),      # 같은 색이 바로 붙음
    ("MON", "13:00", "14:30", "운영체제", "공학관 412", "green"),
    ("TUE", "10:30", "12:00", "확률과 통계", "자연관 105", "orange"),
    ("TUE", "13:30", "15:00", "컴퓨터네트워크", "공학관 208", "purple"),
    ("TUE", "15:00", "18:00", "캡스톤디자인", "공학관 501", "red"),         # 다른 색이 바로 붙음
    ("WED", "09:00", "09:50", "영어회화", "어학관 202", "teal"),            # 50분 수업
    ("WED", "13:00", "14:30", "운영체제", "공학관 412", "green"),
    ("WED", "15:00", "16:30", "근로", "", "pink"),
    ("THU", "10:30", "12:00", "확률과 통계", "자연관 105", "orange"),
    ("THU", "13:30", "15:00", "컴퓨터네트워크", "공학관 208", "purple"),
    ("THU", "15:00", "18:00", "데이터베이스", "공학관 302", "yellow"),
    ("FRI", "14:00", "17:00", "동아리 활동", "", "slate"),
]
# 11/12 실제 캡처에서 본 실수 모양: 끝을 더 내려 읽고, 다음 칸 시작을 그 틀린 끝에 붙이고, 시작을 30분 늦게
MODEL_TIMES = {
    0: ("09:00", "10:30"), 1: ("10:30", "12:30"), 2: ("13:30", "15:00"),
    3: ("10:30", "12:00"), 4: ("13:30", "16:30"), 5: ("16:30", "19:30"),
    6: ("09:00", "10:00"), 7: ("13:30", "15:00"), 8: ("15:00", "16:30"),
    9: ("10:30", "12:00"), 10: ("13:30", "16:30"), 11: ("16:30", "19:30"), 12: ("14:00", "17:30"),
}


def y_of(t):
    h, m = map(int, t.split(":"))
    return TOP + (h - FIRST + m / 60) * HOUR


def main():
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    big = ImageFont.truetype(FONT, 34)
    mid = ImageFont.truetype(FONT, 20)
    small = ImageFont.truetype(FONT, 17)
    d.text((24, 28), "2026년 2학기", fill=(220, 60, 60), font=mid)
    d.text((24, 58), "시간표", fill=(20, 20, 20), font=big)
    grid = (232, 232, 232)
    d.line([(12, TOP - 40), (W - 12, TOP - 40)], fill=grid)
    for i, k in enumerate(DAY_KO):
        x = LEFT + i * COL
        d.line([(x, TOP - 40), (x, y_of(f"{LAST}:00"))], fill=grid)
        d.text((x + COL // 2 - 9, TOP - 32), k, fill=(110, 110, 110), font=mid)
    for h in range(FIRST, LAST + 1):
        y = y_of(f"{h}:00")
        d.line([(12, y), (W - 12, y)], fill=grid)
        if h < LAST:
            d.text((20, y + 4), str(h if h <= 12 else h - 12), fill=(120, 120, 120), font=mid)
    boxes = []
    for i, (day, s, e, name, room, col) in enumerate(CLASSES):
        x0 = LEFT + DAYS.index(day) * COL + 1
        x1 = x0 + COL - 2
        y0, y1 = round(y_of(s)) + 1, round(y_of(e)) - 1      # 정각 선 1픽셀 아래부터, 다음 칸 사이 1픽셀 흰 경계
        d.rounded_rectangle([x0, y0, x1, y1], radius=4, fill=C[col])
        line, ty = "", y0 + 6
        for ch in name:                                       # 칸 너비에 맞춰 줄바꿈
            if d.textlength(line + ch, font=small) > COL - 14:
                d.text((x0 + 6, ty), line, fill="white", font=small); ty += 21; line = ""
            line += ch
        d.text((x0 + 6, ty), line, fill="white", font=small)
        if room:
            d.text((x0 + 6, ty + 22), room, fill=(250, 250, 250), font=small)
        boxes.append((x0, y0, x1, y1))
    img.save(OUT / "tt01-synthetic.png", optimize=True)

    answer = {"source": "합성 그림(scripts/eval/timetable/make_synthetic.py). 과목·강의실·시각은 모두 지어낸 값",
              "classes": [{"dayOfWeek": c[0], "startTime": c[1], "endTime": c[2], "name": c[3]} for c in CLASSES]}
    (OUT / "tt01-synthetic.answer.json").write_text(json.dumps(answer, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    rnd = random.Random(7)
    def n(): return rnd.choice([-1, 1]) * rnd.randint(8, 25)
    classes = []
    for i, ((day, s, e, name, _, _), (x0, y0, x1, y1)) in enumerate(zip(CLASSES, boxes)):
        ms, me = MODEL_TIMES[i]
        classes.append({"dayOfWeek": day, "startTime": ms, "endTime": me, "name": name,
                        "box_2d": [round(y0 / H * 1000 + n()), round(x0 / W * 1000 + rnd.randint(-8, 8)),
                                   round((y1 + 1) / H * 1000 + n()), round(x1 / W * 1000 + rnd.randint(-8, 8))]})
    hours = []
    for h in range(FIRST, LAST):
        y = y_of(f"{h}:00") + 6
        hours.append({"hour": h, "box_2d": [round(y / H * 1000 + rnd.randint(-6, 6)), 27,
                                            round((y + 18) / H * 1000 + rnd.randint(-6, 6)), 60]})
    (OUT / "tt01-synthetic-hand-made-model-answer.json").write_text(
        json.dumps({"hours": hours, "classes": classes}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print("ok", OUT)


if __name__ == "__main__":
    sys.exit(main())
