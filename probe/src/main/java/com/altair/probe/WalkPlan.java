package com.altair.probe;

import java.util.ArrayList;
import java.util.List;

/** 走位参数、真实时长与注入范围的纯计算，供生产调用及离线回归共用。 */
public final class WalkPlan {

    private WalkPlan() { }

    /** 两个原点之间的停顿：`hold` 结束后 `walkOnly` 睡 250ms。 */
    public static final long PAUSE_BETWEEN_MS = 250L;

    /** 三段走完到跳跃之间的等待（`walk()` 里写死的 `pause(1000)`）。 */
    public static final long PAUSE_BEFORE_JUMP_MS = 1000L;

    /** 让路矩形在注入区四周留的余量，与 [InjectShield] 的调用方保持一致。 */
    public static final int SHIELD_MARGIN = 48;

    public static final String MODE_WALK3 = "walk3";
    public static final String MODE_WALK = "walk";

    public static final class Plan {
        public final boolean jump;
        public final List<String> args;
        public final long budgetMs;
        public final TouchAction action;
        private final List<int[]> points;

        Plan(TouchAction action, long budgetMs, List<int[]> points) {
            this.action = action;
            this.jump = action.jump;
            this.args = java.util.Collections.unmodifiableList(action.rootArguments());
            this.budgetMs = budgetMs;
            this.points = java.util.Collections.unmodifiableList(new ArrayList<>(points));
        }

        /** 本轮真正会被注入的坐标，`[x, y]`。给校验和回归测试用。 */
        public List<int[]> points() {
            return points;
        }

        public List<int[]> getPoints() {
            return points;
        }

        public boolean isJump() {
            return jump;
        }

        public List<String> getArgs() {
            return args;
        }

        public long getBudgetMs() {
            return budgetMs;
        }

        /** 四点让路矩形：left, top, right, bottom。 */
        public int[] shield(int width, int height) {
            requireSize(width, height);
            int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = Integer.MIN_VALUE, b = Integer.MIN_VALUE;
            for (int[] p : points) {
                l = Math.min(l, p[0]);
                t = Math.min(t, p[1]);
                r = Math.max(r, p[0]);
                b = Math.max(b, p[1]);
            }
            return new int[] {
                Math.max(0, l - SHIELD_MARGIN), Math.max(0, t - SHIELD_MARGIN),
                Math.min(width, r + SHIELD_MARGIN), Math.min(height, b + SHIELD_MARGIN)
            };
        }
    }

    /**
     * 三段等幅推杆的时间预算。
     *
     * 三段按压 = `d + 2d + d = 4d`，这只是**按压时长**；真实总时长还要加两段 250ms 停顿
     * ；不跳跃时没有额外的 1 秒等待。
     */
    public static long walkOnlyBudgetMs(long legMs) {
        requireLeg(legMs);
        return 4 * legMs + 2 * PAUSE_BETWEEN_MS;
    }

    /** 三段走位 + 等待 1 秒 + 跳跃按压。 */
    public static long walkWithJumpBudgetMs(long legMs, long jumpMs) {
        requireLeg(legMs);
        requireJump(jumpMs);
        return walkOnlyBudgetMs(legMs) + PAUSE_BEFORE_JUMP_MS + jumpMs;
    }

    /** 未标记跳跃：只走三段。 */
    public static Plan walkOnly(int cx, int cy, int left, int right, long legMs,
                                int width, int height) {
        requireLeg(legMs);
        requireSize(width, height);
        int offset = cx - left;
        check(right - cx == offset, "三段推杆必须等幅：左右端点相对轮盘中心偏移不一致");
        check(offset > 0, "推杆幅度为 0，走位不会有任何位移");
        List<int[]> points = java.util.Arrays.asList(new int[] { cx, cy }, new int[] { left, cy }, new int[] { right, cy });
        validate(points, width, height, "走位");
        return new Plan(TouchAction.walk(cx, cy, left, right, legMs, false, 0, 0, 0),
                walkOnlyBudgetMs(legMs), points);
    }

    /** 标记了跳跃：三段 + 等待 1 秒 + 跳一次。 */
    public static Plan walkWithJump(int cx, int cy, int left, int right, int jx, int jy,
                                    long legMs, long jumpMs, int width, int height) {
        requireJump(jumpMs);
        Plan base = walkOnly(cx, cy, left, right, legMs, width, height);
        List<int[]> points = new ArrayList<>(base.points());
        points.add(new int[] { jx, jy });
        validate(points, width, height, "走位与跳跃");
        return new Plan(TouchAction.walk(cx, cy, left, right, legMs, true, jx, jy, jumpMs),
                walkWithJumpBudgetMs(legMs, jumpMs), points);
    }

    /** 单点点击的坐标校验（技能/BUFF 走这条）。 */
    public static int[] validatePoint(int x, int y, int width, int height, String what) {
        requireSize(width, height);
        check(x >= 0 && y >= 0 && x < width && y < height,
                what + "坐标超出屏幕：(" + x + "," + y + ") 不在 " + width + "×" + height + " 内");
        return new int[] { x, y };
    }

    /** 归一化坐标必须先落在 0~1，否则乘上宽高之后可能悄悄变成边界上的一个点。 */
    public static void requireNormalized(float nx, float ny, String what) {
        check(Float.isFinite(nx) && Float.isFinite(ny) && nx >= 0f && nx <= 1f && ny >= 0f && ny <= 1f,
                what + "的归一化坐标越界：(" + nx + ", " + ny + ") 应在 0~1 之间，请重新标记");
    }

    private static void validate(List<int[]> points, int width, int height, String what) {
        for (int[] p : points) {
            validatePoint(p[0], p[1], width, height, what);
        }
    }

    private static void requireLeg(long legMs) {
        check(legMs >= 100 && legMs <= 10_000, "单程时长越界：" + legMs + "ms 应在 100~10000ms 之间");
    }

    private static void requireJump(long jumpMs) {
        check(jumpMs >= 30 && jumpMs <= 600, "跳跃时长越界：" + jumpMs + "ms 应在 30~600ms 之间");
    }

    private static void requireSize(int width, int height) {
        check(width > 0 && height > 0, "屏幕尺寸无效：" + width + "×" + height);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
