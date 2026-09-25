package com.altair.probe;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 与 Android 解耦的动作时序；生产输入和无设备回归测试共用。
 *
 * ## 注入被拒是常态，不是异常
 * 触摸的最终落点是系统 InputDispatcher 决定的，而它在几种情况下会**丢弃**一次注入
 * 并返回 false：目标位置此刻没有可触摸窗口、上一个事件还没派发完、游戏正卡在加载。
 * v0.26.4 用的是 `input swipe` 子进程，**从不检查有没有真的送到**，所以这些丢包
 * 一直是静默的；换成 `injectInputEvent` 并检查返回值之后，它们第一次变成了硬失败 ——
 * 实机日志里表现为"三段走位都成功了，只有最后那一跳被系统拒绝，于是整轮判定失败"。
 *
 * 所以这里对每个事件做**有限重试**（[SEND_ATTEMPTS]），而不是一被拒就把整次动作判死。
 *
 * ## 松手的前提是确实按下过
 * [hold] 把 DOWN 放在 try 之外：DOWN 自己就被拒时不该再发 UP ——
 * 那等于朝一根没按下去的手指发释放事件，轻则被丢弃，重则被当成一次孤立 UP。
 */
public final class GestureSequence {
    public interface Clock {
        long now();
        void sleep(long ms) throws InterruptedException;
    }
    public interface Input {
        void send(int action, long downTime, long eventTime, int x, int y) throws Exception;
    }
    public static final int DOWN = 0, UP = 1, MOVE = 2;

    /** 单个事件最多发几次（含首次）。3 次足够跨过窗口切换那几百毫秒。 */
    private static final int SEND_ATTEMPTS = 3;

    /** 重试之间的间隔。太短没意义（系统还没缓过来），太长会把按压时长拉变形。 */
    private static final long SEND_RETRY_MS = 40;

    private final Clock clock;
    private final Input input;
    private final BooleanSupplier cancelled;
    private final Consumer<String> notice;

    public GestureSequence(Clock clock, Input input, BooleanSupplier cancelled) {
        this(clock, input, cancelled, message -> { });
    }

    public GestureSequence(Clock clock, Input input, BooleanSupplier cancelled,
                           Consumer<String> notice) {
        this.clock = clock;
        this.input = input;
        this.cancelled = cancelled;
        this.notice = notice;
    }

    private void check() throws InterruptedException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("动作已取消");
        }
    }

    /** 发一个事件；被系统丢弃时重试。取消/中断立即放弃，不浪费重试次数。 */
    private void send(int action, long down, long time, int x, int y) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                input.send(action, down, time, x, y);
                return;
            } catch (Exception e) {
                if (attempt >= SEND_ATTEMPTS || cancelled.getAsBoolean()
                        || Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                clock.sleep(SEND_RETRY_MS);
                check();
            }
        }
    }

    /**
     * 松手专用：只重试，绝不抛。
     *
     * 松手发生在 `finally` 里，那里再抛异常会**盖掉真正的失败原因** ——
     * 排查时看到的就变成 UP 的错误，而真正的问题在 DOWN 或 MOVE 上。
     */
    private void quietSend(int action, long down, long time, int x, int y) {
        try {
            send(action, down, time, x, y);
        } catch (Exception ignored) {
            // 已经在收尾了；失败也没什么能做的，且不能覆盖真实原因。
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

    /**
     * 在 (cx,cy) 按下，拖到 (x,y) 并保持 [duration] 毫秒，然后松手。
     *
     * 保持期间每 ~16ms 补一个 MOVE：很多游戏用"有没有持续的 MOVE"判断手指是否还在，
     * 只在开头发一个 MOVE 然后干等，游戏会把摇杆当成已经回中。
     */
    public void hold(int cx, int cy, int x, int y, long duration) throws Exception {
        if (duration < 1 || duration > 20_000) throw new IllegalArgumentException("按压时长越界");
        check();
        long down = clock.now();

        // ★ DOWN 在 try 之外：它自己失败就没有"已按下"这回事，也就不该有 UP。
        send(DOWN, down, down, cx, cy);
        try {
            check();
            send(MOVE, down, clock.now(), x, y);
            long end = clock.now() + duration;
            while (clock.now() < end) {
                check();
                clock.sleep(Math.min(16, end - clock.now()));
                check();
                if (clock.now() < end) send(MOVE, down, clock.now(), x, y);
            }
        } finally {
            // 无论成功、被取消还是中途注入失败，只要按下过就必须松手 ——
            // 否则游戏里会留下一根永远按着的手指。
            quietSend(UP, down, clock.now(), x, y);
        }
        check();
    }

    /**
     * 三段走位 + 收尾跳一下。
     *
     * ★ **跳跃失败不算整轮失败**。三段走位已经把角色推出去又拉回来了，那一跳只是点缀
     * （跳跃坐标本来就是可选项）。把它算成失败会让"人已经走完一轮"的战果被一个装饰动作
     * 抹掉，还会连锁触发引擎的失败熔断把任务停掉 —— 实机日志里正是这个现象。
     */
    public void walk(int cx, int cy, int left, int right, int jx, int jy,
                     long legMs, long jumpMs) throws Exception {
        if (jumpMs < 30 || jumpMs > 600) throw new IllegalArgumentException("跳跃时长越界");
        walkOnly(cx, cy, left, right, legMs);
        pause(1000);
        try {
            hold(jx, jy, jx, jy, jumpMs);
        } catch (InterruptedException e) {
            throw e;                       // 用户取消：照旧向上传，别吞掉
        } catch (Exception e) {
            notice.accept("TOUCH_JUMP_FAILED " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
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
