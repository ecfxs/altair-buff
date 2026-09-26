package com.altair.probe;

import java.util.Arrays;
import java.util.List;

/** 两种输入后端共用的动作描述；Root 命令编码只在适配边界使用。 */
public final class TouchAction {
    public final boolean walk, jump;
    public final int cx, cy, left, right, jx, jy;
    public final long durationMs, jumpMs;

    private TouchAction(boolean walk, boolean jump, int cx, int cy, int left, int right,
                        int jx, int jy, long durationMs, long jumpMs) {
        this.walk = walk; this.jump = jump;
        this.cx = cx; this.cy = cy; this.left = left; this.right = right;
        this.jx = jx; this.jy = jy; this.durationMs = durationMs; this.jumpMs = jumpMs;
    }

    public static TouchAction tap(int x, int y, long ms) {
        return new TouchAction(false, false, x, y, x, x, 0, 0, ms, 0);
    }

    public static TouchAction walk(int cx, int cy, int left, int right, long ms,
                                   boolean jump, int jx, int jy, long jumpMs) {
        return new TouchAction(true, jump, cx, cy, left, right, jx, jy, ms, jumpMs);
    }

    public List<String> rootArguments() {
        if (!walk) return Arrays.asList("tap", "" + cx, "" + cy, "" + durationMs);
        if (!jump) return Arrays.asList("walk3", "" + cx, "" + cy, "" + left, "" + right, "" + durationMs);
        return Arrays.asList("walk", "" + cx, "" + cy, "" + left, "" + right,
                "" + jx, "" + jy, "" + durationMs, "" + jumpMs);
    }
}
