package kr.ac.chosun.mspace.assistant.agent.chat.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

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
            Box b = new Box(v[0], v[1], v[2], v[3]);
            boolean inRange = Arrays.stream(v).allMatch(x -> x >= 0 && x <= 1000);
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

    private final BufferedImage img;
    private final int width;
    private final int height;

    private TimetableGeometry(BufferedImage img) {
        this.img = img;
        this.width = img.getWidth();
        this.height = img.getHeight();
    }

    /** PNG·JPEG만(자바 기본 ImageIO). WebP나 깨진 그림이면 빈 값 → 모델 시각을 쓴다 */
    static Optional<TimetableGeometry> of(byte[] image) {
        if (image == null || image.length == 0) {
            return Optional.empty();
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
            return img == null || img.getWidth() < 50 || img.getHeight() < 50 ? Optional.empty() : Optional.of(new TimetableGeometry(img));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // ---------- 1. 시간 눈금 ----------

    /**
     * 눈금 글자 2개 이상으로 직선을 맞추고, 정각 가로줄이 보이면 그 줄들로 다시 맞춘다.
     * 글자는 줄 바로 아래(에브리타임)나 줄 가운데(다른 앱)에 놓여 위치가 앱마다 조금 다르지만, 가로줄은 정확하다.
     */
    Optional<Calibration> calibrate(List<HourLabel> labels) {
        List<double[]> points = new ArrayList<>();
        for (HourLabel l : labels) {
            if (l.hour() >= 0 && l.hour() <= 24) {
                points.add(new double[] {l.hour(), py(l.box().ymin())});
            }
        }
        Optional<Calibration> rough = fit(points, false);
        if (rough.isEmpty()) {
            return Optional.empty();
        }
        Calibration c = rough.get();
        // 정각 선: 눈금으로 본 시가 정수에 가까운(±0.3시간) 가로줄만. 30분 점선·머리줄은 여기서 걸러진다
        List<double[]> grid = new ArrayList<>();
        for (double y : horizontalLines()) {
            double h = c.hourAt(y);
            long hour = Math.round(h);
            if (Math.abs(h - hour) <= 0.3 && hour >= 0 && hour <= 24 && grid.stream().noneMatch(p -> p[0] == hour)) {
                grid.add(new double[] {hour, y});
            }
        }
        Optional<Calibration> fine = fit(grid, true);
        // 가로줄 기울기가 눈금 글자 기울기와 크게 다르면(다른 줄을 잡음) 글자 쪽을 믿는다
        if (fine.isPresent() && Math.abs(fine.get().slope() - c.slope()) <= 0.15 * c.slope()) {
            return fine;
        }
        return rough;
    }

    private static Optional<Calibration> fit(List<double[]> points, boolean grid) {
        if (points.stream().map(p -> p[0]).distinct().count() < 2) {
            return Optional.empty();
        }
        double n = points.size();
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (double[] p : points) {
            sx += p[0];
            sy += p[1];
            sxx += p[0] * p[0];
            sxy += p[0] * p[1];
        }
        double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        double intercept = (sy - slope * sx) / n;
        // 한 시간이 그림 높이의 1.5%보다 작으면 읽을 수 없는 그림(또는 hour를 거꾸로 적음)
        return slope > 0 ? Optional.of(new Calibration(intercept, slope, grid)) : Optional.empty();
    }

    /** 옅은 회색 가로줄(시간표 칸 선)의 y. 무채색 픽셀 중 절반 이상이 옅은 회색인 줄을 모아 묶음 가운데를 쓴다 */
    List<Double> horizontalLines() {
        List<Double> lines = new ArrayList<>();
        int runStart = -1;
        for (int y = 0; y <= height; y++) {
            boolean line = y < height && isLineRow(y);
            if (line && runStart < 0) {
                runStart = y;
            } else if (!line && runStart >= 0) {
                lines.add((runStart + y - 1) / 2.0);
                runStart = -1;
            }
        }
        return lines;
    }

    private boolean isLineRow(int y) {
        int neutral = 0;
        int gray = 0;
        for (int x = 0; x < width; x++) {
            int rgb = img.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 16) {
                neutral++;
                double lum = (r + g + b) / 3.0;
                if (lum >= 200 && lum <= 250) {
                    gray++;
                }
            }
        }
        return neutral >= 0.2 * width && gray >= 0.5 * neutral;
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
        int[] color = blockColor(x0 + w / 5, x1 - w / 5, y0 + (y1 - y0) / 4, y1 - (y1 - y0) / 4);
        if (color.length == 0) {
            return Optional.empty();
        }
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
            tops.add(extend(x, seed, -1, top, maxGap, color));
            bottoms.add(extend(x, seed, +1, bottom, maxGap, color));
        }
        if (tops.size() < 3) {
            return Optional.empty();
        }
        // 픽셀 끝 → 선 위치: 위 끝은 그 픽셀 위쪽 가장자리, 아래 끝은 아래쪽 가장자리
        return Optional.of(new double[] {median(tops), median(bottoms) + 1});
    }

    /** 상자 가운데 영역에서 채도가 있는 픽셀을 거칠게 묶어 가장 많은 색. 색칠 안 된 상자면 빈 배열 */
    private int[] blockColor(int xa, int xb, int ya, int yb) {
        java.util.Map<Integer, int[]> buckets = new java.util.HashMap<>();
        for (int y = Math.max(0, ya); y <= Math.min(height - 1, yb); y++) {
            for (int x = Math.max(0, xa); x <= Math.min(width - 1, xb); x++) {
                int[] c = rgb(x, y);
                if (Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2])) < 40) {
                    continue;  // 흰 글자·바탕·회색 선
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
        if (best == null || best[3] < 10) {
            return new int[0];
        }
        return new int[] {best[0] / best[3], best[1] / best[3], best[2] / best[3]};
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

    private int extend(int x, int from, int dir, int limit, int maxGap, int[] color) {
        int last = from;
        for (int y = from + dir; dir < 0 ? y >= limit : y <= limit; y += dir) {
            if (same(x, y, color)) {
                last = y;
            } else if (Math.abs(y - last) > maxGap) {
                break;
            }
        }
        return last;
    }

    private boolean same(int x, int y, int[] color) {
        int[] c = rgb(x, y);
        double d = Math.sqrt(Math.pow(c[0] - color[0], 2) + Math.pow(c[1] - color[1], 2) + Math.pow(c[2] - color[2], 2));
        return d <= COLOR_TOLERANCE;
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

    private static double median(List<Integer> v) {
        List<Integer> s = v.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }
}
