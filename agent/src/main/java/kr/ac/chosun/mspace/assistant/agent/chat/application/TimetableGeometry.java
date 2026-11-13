package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 시간표 캡처에서 칸의 위·아래 끝을 <b>픽셀로 재서</b> 시각으로 바꾼다 (모델은 "어디쯤"만, 코드가 "정확히 몇 시").
 * <p>
 * 모델(비전)은 과목명·요일은 잘 읽지만 칸 끝이 눈금 몇 칸째인지 세는 데 약하다. 실제로 끝 시각을 다음 칸 시작에 붙이거나
 * 30분씩 밀려 읽었다(트러블슈팅 TS-18). 그래서 모델에게는 칸과 시간 눈금 글자의 대략 위치(box_2d, 0~1000)만 받고,
 * <ol>
 *   <li>시간 눈금: 눈금 글자 위치로 "y → 시각" 직선을 맞춘 뒤, 그림의 옅은 가로줄(정각 선)에 다시 맞춰 보정한다</li>
 *   <li>칸 끝: 모델 상자 가운데 색을 칸 색으로 보고, 세로로 몇 줄 훑어 같은 색이 이어지는 위·아래 끝을 찾는다</li>
 *   <li>그 픽셀 끝을 시각으로 바꿔 30분 칸(6분 안) 또는 5분 단위로 맞춘다</li>
 * </ol>
 * 하나라도 못 하면 빈 값을 돌려주고, 호출자는 모델이 적은 시각을 그대로 쓴다(지금보다 나빠지지 않게).
 * 그림은 메모리에서만 읽고 저장·로그하지 않는다.
 */
final class TimetableGeometry {

    /** 상자 좌표는 Gemini box_2d 규약: [ymin, xmin, ymax, xmax], 그림 전체를 0~1000으로 본 값 */
    record Box(double ymin, double xmin, double ymax, double xmax) {

        static Optional<Box> of(double[] v) {
            if (v == null || v.length != 4) {
                return Optional.empty();
            }
            // 그림 가장자리에 걸친 글자는 모델이 -3, 1004처럼 조금 넘겨 적기도 해서 20까지는 잘라 쓴다
            boolean inRange = Arrays.stream(v).allMatch(x -> x >= -20 && x <= 1020);
            double[] c = Arrays.stream(v).map(x -> Math.max(0, Math.min(1000, x))).toArray();
            Box b = new Box(c[0], c[1], c[2], c[3]);
            return inRange && b.ymax > b.ymin && b.xmax > b.xmin ? Optional.of(b) : Optional.empty();
        }
    }

    /** 눈금 글자 하나: 24시간 hour + 글자 위치 */
    record HourLabel(int hour, Box box) {
    }

    /** y(픽셀) = intercept + slope × 시(hour, 소수). slope = 한 시간의 픽셀 수 */
    record Calibration(double intercept, double slope, boolean gridAligned) {

        double hourAt(double y) {
            return (y - intercept) / slope;
        }
    }

    /** 칸 색과 같다고 볼 RGB 거리. JPEG 잡티는 넘기고 옆 칸의 다른 색(거리 35 이상)은 가른다 */
    static final double COLOR_TOLERANCE = 30;
    /** 시각을 맞출 단위(분). 칸 끝은 픽셀 1~2개 오차라 5분이면 충분하고, 50분 수업도 살린다 */
    static final int ROUND_MINUTES = 5;
    /** 30분 칸(정각·30분)에 이만큼 가까우면 그 칸으로 붙인다. 대학 시간표 대부분이 30분 칸이고, 칸 끝 1~2픽셀 오차를 흡수한다 */
    static final int SNAP_MINUTES = 6;
    /** 풀기 전에 막는 픽셀 수(약 4000×4000). 폰 캡처는 보통 1200×2700 안팎이다 */
    static final long MAX_PIXELS = 16_000_000L;

    private final BufferedImage img;
    private final int width;
    private final int height;
    /** 바탕색 {r, g, b, 밝기}. 밝은·다크 모드 모두 이 색을 기준으로 선과 칸을 가른다 */
    private final int[] background;

    private TimetableGeometry(BufferedImage img) {
        this.img = img;
        this.width = img.getWidth();
        this.height = img.getHeight();
        this.background = background(img);
    }

    /** PNG·JPEG만(자바 기본 ImageIO). WebP나 깨진 그림이면 빈 값 → 모델 시각을 쓴다 */
    static Optional<TimetableGeometry> of(byte[] image) {
        if (image == null || image.length == 0) {
            return Optional.empty();
        }
        // 5MB 안이라도 압축이 잘 되는 PNG는 픽셀이 매우 많을 수 있어, 풀기 전에 머리말로 크기부터 본다(메모리 부족 막기)
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            Iterator<ImageReader> readers = in == null ? java.util.Collections.emptyIterator() : ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w < 50 || h < 50 || (long) w * h > MAX_PIXELS) {
                    return Optional.empty();
                }
                BufferedImage img = reader.read(0);
                return img == null ? Optional.empty() : Optional.of(new TimetableGeometry(img));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // ---------- 1. 시간 눈금 ----------

    /**
     * 눈금 글자로 "대략 y → 시각"을 잡고, 그림에서 찾은 정각 가로줄의 간격·위치로 정확히 맞춘다 (TS-19).
     * <ul>
     *   <li>눈금 글자: 12시간으로 적힌 오후 숫자(1, 2, 3 …)는 위에서 아래로 줄어들면 12를 더해 고치고, 한두 개 틀린 글자는 중앙값 기울기(Theil–Sen)로 버틴다</li>
     *   <li>글자가 줄 바로 아래(에브리타임)·칸 가운데·줄 위에 걸침처럼 앱마다 달라, 글자 위치를 그대로 시각으로 쓰지 않는다.
     *       가로줄들이 한 시간 간격으로 놓인 "위상"을 찾고, 글자 가운데가 그 줄과 같은 높이이거나 그 아래 0.65시간 안에 있다고 보고 정각을 정한다</li>
     *   <li>가로줄은 바탕색과 조금 다른 무채색 긴 선으로 찾는다(밝은·다크 모드 모두). 30분 실선이 같이 있으면 더 진한 쪽을 정각으로 본다</li>
     * </ul>
     * 가로줄을 못 맞추면 글자만으로 맞춘 값(gridAligned=false)을 돌려주고, 호출자는 모델 시각과 견줘 쓴다.
     */
    Optional<Calibration> calibrate(List<HourLabel> labels) {
        List<double[]> points = labelPoints(labels);
        Optional<double[]> rough = theilSen(points);
        if (rough.isEmpty() || rough.get()[1] < 0.015 * height) {
            return Optional.empty();
        }
        double a = rough.get()[0];
        double p = rough.get()[1];
        List<double[]> lines = horizontalLines();          // {y, 세기}
        // 가로줄 위상: f = (y - a) / p 의 소수 부분이 모이는 곳. 0.02 간격 원형 히스토그램, ±0.08 안의 세기 합
        double bestPhase = Double.NaN;
        double bestScore = 0;
        for (int k = 0; k < 50; k++) {
            double phi = k / 50.0;
            double score = 0;
            for (double[] l : lines) {
                if (circular(frac((l[0] - a) / p) - phi) <= 0.08) {
                    score += l[1];
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestPhase = phi;
            }
        }
        if (Double.isNaN(bestPhase)) {
            return Optional.of(new Calibration(a, p, false));
        }
        // 글자 가운데는 정각 줄과 같은 높이 ~ 0.65시간 아래. 줄 위상 c(0~1)를 그 범위로 옮긴다(-0.65 ~ 0.35)
        double shift = bestPhase > 0.35 ? bestPhase - 1 : bestPhase;
        List<double[]> grid = new ArrayList<>();
        for (double[] l : lines) {
            double f = (l[0] - a) / p;
            if (circular(frac(f) - bestPhase) <= 0.12) {
                long hour = Math.round(f - shift);
                if (hour >= 0 && hour <= 24) {
                    grid.add(new double[] {hour, l[0]});
                }
            }
        }
        Optional<double[]> fine = theilSen(grid);
        // 줄 간격이 글자 간격과 15% 넘게 다르면(다른 줄을 잡음) 글자 쪽을 믿는다
        if (fine.isPresent() && grid.size() >= 3 && Math.abs(fine.get()[1] - p) <= 0.15 * p) {
            return Optional.of(new Calibration(fine.get()[0], fine.get()[1], true));
        }
        return Optional.of(new Calibration(a, p, false));
    }

    /** 눈금 글자 → {시, 글자 가운데 y}. 위에서 아래로 읽어 시가 줄면(12시간 표기) 12를 더한다 */
    private List<double[]> labelPoints(List<HourLabel> labels) {
        List<HourLabel> sorted = labels.stream().filter(l -> l.hour() >= 0 && l.hour() <= 24)
                .sorted(java.util.Comparator.comparingDouble(l -> l.box().ymin() + l.box().ymax())).toList();
        List<double[]> points = new ArrayList<>();
        int prev = -1;
        for (HourLabel l : sorted) {
            int hour = l.hour();
            if (prev >= 0 && hour < prev && hour + 12 <= 24 && hour + 12 > prev) {
                hour += 12;
            }
            prev = Math.max(prev, hour);
            points.add(new double[] {hour, py((l.box().ymin() + l.box().ymax()) / 2)});
        }
        return points;
    }

    /** {절편, 기울기}: 기울기는 두 점씩 짝지은 기울기의 중앙값, 절편은 (y - 기울기×시)의 중앙값. 틀린 점 몇 개에 흔들리지 않는다 */
    private static Optional<double[]> theilSen(List<double[]> points) {
        List<Double> slopes = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                double dx = points.get(j)[0] - points.get(i)[0];
                if (dx != 0) {
                    slopes.add((points.get(j)[1] - points.get(i)[1]) / dx);
                }
            }
        }
        if (slopes.isEmpty()) {
            return Optional.empty();
        }
        double slope = medianD(slopes);
        if (slope <= 0) {
            return Optional.empty();
        }
        double intercept = medianD(points.stream().map(pt -> pt[1] - slope * pt[0]).toList());
        return Optional.of(new double[] {intercept, slope});
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    /** 원형(0~1) 거리 */
    private static double circular(double d) {
        double f = frac(d);
        return Math.min(f, 1 - f);
    }

    /**
     * 가로줄의 {y, 세기}. 한 줄에서 "바탕보다 조금 밝거나 어두운 무채색" 픽셀이 너비의 15% 이상이고 이어진 구간이 6% 이상이면 선으로 본다.
     * 진한 글자(바탕과 차이 90 넘음)·점선(짧게 끊김)은 빠진다. 붙은 줄은 묶어 가운데를 쓰고, 세기 = 덮은 픽셀 수 × 바탕과의 밝기 차이
     */
    List<double[]> horizontalLines() {
        List<double[]> lines = new ArrayList<>();
        int runStart = -1;
        double acc = 0;
        for (int y = 0; y <= height; y++) {
            double w = y < height ? lineWeight(y) : 0;
            if (w > 0 && runStart < 0) {
                runStart = y;
                acc = w;
            } else if (w > 0) {
                acc = Math.max(acc, w);
            } else if (runStart >= 0) {
                lines.add(new double[] {(runStart + y - 1) / 2.0, acc});
                runStart = -1;
            }
        }
        return lines;
    }

    private double lineWeight(int y) {
        int count = 0;
        int run = 0;
        int longest = 0;
        double contrast = 0;
        for (int x = 0; x < width; x++) {
            int[] c = rgb(x, y);
            double diff = Math.abs((c[0] + c[1] + c[2]) / 3.0 - background[3]);
            boolean neutral = Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2])) < 16;
            if (neutral && diff >= 5 && diff <= 90) {
                count++;
                run++;
                contrast += diff;
                longest = Math.max(longest, run);
            } else {
                run = 0;
            }
        }
        return count >= 0.15 * width && longest >= 0.06 * width ? contrast : 0;
    }

    /** 바탕색 {r, g, b, 밝기}: 성기게 훑어 가장 흔한 색 */
    private static int[] background(BufferedImage img) {
        java.util.Map<Integer, int[]> buckets = new java.util.HashMap<>();
        int stepX = Math.max(1, img.getWidth() / 60);
        int stepY = Math.max(1, img.getHeight() / 120);
        for (int y = 0; y < img.getHeight(); y += stepY) {
            for (int x = 0; x < img.getWidth(); x += stepX) {
                int v = img.getRGB(x, y);
                int r = (v >> 16) & 0xFF;
                int g = (v >> 8) & 0xFF;
                int b = v & 0xFF;
                int[] acc = buckets.computeIfAbsent((r / 8) << 10 | (g / 8) << 5 | (b / 8), k -> new int[4]);
                acc[0] += r;
                acc[1] += g;
                acc[2] += b;
                acc[3]++;
            }
        }
        int[] best = buckets.values().stream().max(java.util.Comparator.comparingInt(a -> a[3])).orElse(new int[] {255, 255, 255, 1});
        int r = best[0] / best[3];
        int g = best[1] / best[3];
        int b = best[2] / best[3];
        return new int[] {r, g, b, (r + g + b) / 3};
    }

    // ---------- 2. 칸 끝 ----------

    /**
     * 모델 상자 안 가운데 색을 칸 색으로 잡고, 세로 다섯 줄을 위·아래로 훑어 같은 색의 끝을 찾는다.
     * 글자(흰색)는 칸 안에 끼어 있으니 한 시간의 40%까지는 끊겨도 이어 본다. 다섯 줄 끝의 가운데 값(중앙값)을 쓴다.
     *
     * @return {위 끝 y, 아래 끝 y} 픽셀
     */
    Optional<double[]> measure(Box box, Calibration cal) {
        int x0 = px(box.xmin());
        int x1 = px(box.xmax());
        int y0 = (int) Math.round(py(box.ymin()));
        int y1 = (int) Math.round(py(box.ymax()));
        int w = x1 - x0;
        if (w < 6 || y1 - y0 < 4) {
            return Optional.empty();
        }
        int[] found = blockColor(x0 + w / 5, x1 - w / 5, y0 + (y1 - y0) / 4, y1 - (y1 - y0) / 4);
        if (found.length == 0) {
            return Optional.empty();
        }
        // 옅은 칸은 바탕·회색 선과 색 거리가 가까워, 허용 거리를 칸과 바탕 거리의 절반으로 줄인다(12~30)
        double tolerance = Math.max(12, Math.min(COLOR_TOLERANCE, distance(found, background) / 2));
        int[] color = {found[0], found[1], found[2], (int) Math.round(tolerance)};
        // 모델 상자가 30분 가까이 어긋나도 찾게 위아래로 0.45시간 더 본다. 그보다 멀면 다른 칸일 수 있어 보지 않는다
        int margin = (int) Math.round(0.45 * cal.slope());
        int maxGap = (int) Math.round(0.4 * cal.slope());
        int top = Math.max(0, y0 - margin);
        int bottom = Math.min(height - 1, y1 + margin);
        int mid = (y0 + y1) / 2;
        List<Integer> tops = new ArrayList<>();
        List<Integer> bottoms = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            int x = x0 + w * i / 6;
            int seed = seed(x, mid, y0, y1, color);
            if (seed < 0) {
                continue;
            }
            tops.add(extend(x, seed, -1, top, maxGap, color, x0 + w / 10, x1 - w / 10));
            bottoms.add(extend(x, seed, +1, bottom, maxGap, color, x0 + w / 10, x1 - w / 10));
        }
        if (tops.size() < 3) {
            return Optional.empty();
        }
        // 픽셀 끝 → 선 위치: 위 끝은 그 픽셀 위쪽 가장자리, 아래 끝은 아래쪽 가장자리
        return Optional.of(new double[] {median(tops), median(bottoms) + 1});
    }

    /**
     * 상자 가운데 영역에서 바탕과 다른 픽셀을 거칠게 묶어 가장 많은 색 = 칸 색. 옅은 색(채도 낮음)·다크 모드 칸도 잡는다.
     * 그 색이 영역의 25%도 안 되면(빈칸 위 선·글자만 걸림) 색칠된 칸이 아니라고 보고 빈 배열
     */
    private int[] blockColor(int xa, int xb, int ya, int yb) {
        java.util.Map<Integer, int[]> buckets = new java.util.HashMap<>();
        int all = 0;
        for (int y = Math.max(0, ya); y <= Math.min(height - 1, yb); y++) {
            for (int x = Math.max(0, xa); x <= Math.min(width - 1, xb); x++) {
                all++;
                int[] c = rgb(x, y);
                if (distance(c, background) < 25) {
                    continue;  // 바탕
                }
                int key = (c[0] / 16) << 8 | (c[1] / 16) << 4 | (c[2] / 16);
                int[] acc = buckets.computeIfAbsent(key, k -> new int[4]);
                acc[0] += c[0];
                acc[1] += c[1];
                acc[2] += c[2];
                acc[3]++;
            }
        }
        int[] best = buckets.values().stream().max(java.util.Comparator.comparingInt(a -> a[3])).orElse(null);
        if (best == null || best[3] < 10 || best[3] < 0.25 * all) {
            return new int[0];
        }
        return new int[] {best[0] / best[3], best[1] / best[3], best[2] / best[3]};
    }

    private static double distance(int[] c, int[] d) {
        return Math.sqrt(Math.pow(c[0] - d[0], 2) + Math.pow(c[1] - d[1], 2) + Math.pow(c[2] - d[2], 2));
    }

    /** 상자 가운데에서 가장 가까운 칸 색 픽셀(글자 위에서 시작하지 않게) */
    private int seed(int x, int mid, int y0, int y1, int[] color) {
        for (int d = 0; d <= (y1 - y0) / 2; d++) {
            for (int y : new int[] {mid - d, mid + d}) {
                if (y >= 0 && y < height && same(x, y, color)) {
                    return y;
                }
            }
        }
        return -1;
    }

    /**
     * 같은 색이 끊겨도 maxGap까지는 글자로 보고 이어 본다. 다만 칸 너비 전체가 꽉 찬 줄 바로 다음에 칸 색이 거의 없는 얇은 줄(3픽셀 이하)이 오고
     * 다시 꽉 찬 줄이 나오면 글자가 아니라 칸 사이 경계선이라 거기서 멈춘다. 글자 줄은 10픽셀 안팎이라 이 조건에 안 걸린다.
     * 같은 색 수업 두 개가 위아래로 붙어도 하나로 합치지 않게.
     */
    private int extend(int x, int from, int dir, int limit, int maxGap, int[] color, int xa, int xb) {
        int last = from;
        for (int y = from + dir; dir < 0 ? y >= limit : y <= limit; y += dir) {
            if (same(x, y, color)) {
                last = y;
            } else if (Math.abs(y - last) > maxGap || separator(y, dir, xa, xb, color) || gap(y, dir, xa, xb, color, maxGap)) {
                break;
            }
        }
        return last;
    }

    private boolean separator(int y, int dir, int xa, int xb, int[] color) {
        if (coverage(y, xa, xb, color) >= 0.1 || coverage(y - dir, xa, xb, color) < 0.9) {
            return false;
        }
        for (int k = 1; k <= 3; k++) {
            int yy = y + dir * k;
            if (yy < 0 || yy >= height) {
                return false;
            }
            if (coverage(yy, xa, xb, color) >= 0.9) {
                return true;
            }
        }
        return false;
    }

    /**
     * 칸 너비 전체에 칸 색이 거의 없는 줄이 한 시간의 10%(최소 3줄) 넘게 이어진 뒤 다시 꽉 찬 줄이 나오면 칸 사이 틈(10분 쉬는 시간 등)이다.
     * 굵은 흰 글자도 한두 줄은 거의 비어 보일 수 있어, 짧게 빈 줄은 글자로 본다
     */
    private boolean gap(int y, int dir, int xa, int xb, int[] color, int maxGap) {
        int need = Math.max(3, (int) Math.round(maxGap / 4.0));
        int n = 0;
        int yy = y;
        while (yy >= 0 && yy < height && n <= maxGap && coverage(yy, xa, xb, color) < 0.1) {
            n++;
            yy += dir;
        }
        return n >= need && n <= maxGap && coverage(yy, xa, xb, color) >= 0.9;
    }

    /** 그 줄(xa~xb)에서 칸 색인 픽셀 비율 */
    private double coverage(int y, int xa, int xb, int[] color) {
        if (y < 0 || y >= height) {
            return 0;
        }
        int hit = 0;
        int all = 0;
        for (int x = Math.max(0, xa); x <= Math.min(width - 1, xb); x++) {
            all++;
            if (same(x, y, color)) {
                hit++;
            }
        }
        return all == 0 ? 0 : (double) hit / all;
    }

    private boolean same(int x, int y, int[] color) {
        return distance(rgb(x, y), color) <= (color.length > 3 ? color[3] : COLOR_TOLERANCE);
    }

    // ---------- 3. 시각 ----------

    /**
     * 픽셀 y → 시각. 칸은 정각 선 1픽셀 아래에서 시작하는 일이 많아(15:00이 15:03으로 잼) 30분 칸에서 6분 안이면 30분 칸으로 붙이고,
     * 아니면 5분 단위(50분 수업 등)로 맞춘다. 0시 전·24시 뒤면 빈 값
     */
    static Optional<LocalTime> timeAt(double y, Calibration cal) {
        double minutes = cal.hourAt(y) * 60;
        long half = Math.round(minutes / 30) * 30;
        long rounded = Math.abs(minutes - half) <= SNAP_MINUTES ? half : Math.round(minutes / ROUND_MINUTES) * ROUND_MINUTES;
        if (rounded < 0 || rounded >= 24 * 60) {
            return Optional.empty();
        }
        return Optional.of(LocalTime.of((int) (rounded / 60), (int) (rounded % 60)));
    }

    private int[] rgb(int x, int y) {
        int v = img.getRGB(x, y);
        return new int[] {(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
    }

    private double py(double v) {
        return v / 1000.0 * height;
    }

    private int px(double v) {
        return (int) Math.round(v / 1000.0 * width);
    }

    private static double medianD(List<Double> v) {
        List<Double> s = v.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    private static double median(List<Integer> v) {
        List<Integer> s = v.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }
}
