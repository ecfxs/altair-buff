package com.altair.probe;

/** Safe display region used for placing overlay windows without covering system bars. */
public final class ScreenBounds {
    public final int left, top, right, bottom;

    private ScreenBounds(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public static ScreenBounds from(int width, int height, int safeLeft, int safeTop,
                                    int safeRight, int safeBottom) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("屏幕尺寸无效");
        int left = Math.max(0, Math.min(safeLeft, width));
        int right = Math.max(left, Math.min(width - Math.max(0, safeRight), width));
        int top = Math.max(0, Math.min(safeTop, height));
        int bottom = Math.max(top, Math.min(height - Math.max(0, safeBottom), height));
        return new ScreenBounds(left, top, right, bottom);
    }

    public int defaultRightX(int windowWidth, int margin) {
        return Math.max(left, right - Math.max(0, windowWidth) - Math.max(0, margin));
    }

    public int defaultTopY(int margin, int windowHeight) {
        int max = Math.max(top, bottom - Math.max(0, windowHeight));
        return Math.max(top, Math.min(top + Math.max(0, margin), max));
    }

    public int clampX(int x, int windowWidth) {
        int max = Math.max(left, right - Math.max(0, windowWidth));
        return Math.max(left, Math.min(x, max));
    }

    public int clampY(int y, int windowHeight) {
        int max = Math.max(top, bottom - Math.max(0, windowHeight));
        return Math.max(top, Math.min(y, max));
    }
}
