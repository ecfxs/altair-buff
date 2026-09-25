package com.altair.probe;

import java.util.function.BooleanSupplier;

/** 与 Android 解耦的动作时序；生产输入和无设备回归测试共用。 */
public final class GestureSequence {
    public interface Clock {
        long now();
        void sleep(long ms) throws InterruptedException;
    }
    public interface Input {
        void send(int action, long downTime, long eventTime, int x, int y) throws Exception;
    }
    public static final int DOWN = 0, UP = 1, MOVE = 2;
    private final Clock clock;
    private final Input input;
    private final BooleanSupplier cancelled;

    public GestureSequence(Clock clock, Input input, BooleanSupplier cancelled) {
        this.clock = clock;
        this.input = input;
        this.cancelled = cancelled;
    }

    private void check() throws InterruptedException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("动作已取消");
        }
    }

    public void pause(long ms) throws InterruptedException {
        long end = clock.now() + ms;
        while (clock.now() < end) {
            check();
            clock.sleep(Math.min(16, end - clock.now()));
        }
        check();
    }

    public void hold(int cx, int cy, int x, int y, long duration) throws Exception {
        if (duration < 1 || duration > 20_000) throw new IllegalArgumentException("按压时长越界");
        check();
        long down = clock.now();
        try {
            input.send(DOWN, down, down, cx, cy);
            check();
            input.send(MOVE, down, clock.now(), x, y);
            long end = clock.now() + duration;
            while (clock.now() < end) {
                check();
                clock.sleep(Math.min(16, end - clock.now()));
                check();
                if (clock.now() < end) input.send(MOVE, down, clock.now(), x, y);
            }
        } finally {
            // 即使 MOVE 失败或用户取消，也尝试释放同一根手指。
            input.send(UP, down, clock.now(), x, y);
        }
        check();
    }

    public void walk(int cx, int cy, int left, int right, int jx, int jy,
                     long legMs, long jumpMs) throws Exception {
        if (jumpMs < 30 || jumpMs > 600) throw new IllegalArgumentException("跳跃时长越界");
        walkOnly(cx, cy, left, right, legMs);
        pause(1000);
        hold(jx, jy, jx, jy, jumpMs);
    }

    /** 只走三段、不跳：跳跃未标记时的退路，避免因为可选的一项把整次走位判失败。 */
    public void walkOnly(int cx, int cy, int left, int right, long legMs) throws Exception {
        if (legMs < 100 || legMs > 10_000) {
            throw new IllegalArgumentException("走位参数越界");
        }
        hold(cx, cy, left, cy, legMs);
        pause(250);
        hold(cx, cy, right, cy, legMs * 2);
        pause(250);
        hold(cx, cy, left, cy, legMs);
    }
}
