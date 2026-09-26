package com.altair.probe;

/** 无 Android 依赖的续接手势收尾逻辑。回调完成与手指释放是两个不同状态。 */
public final class AccessibleGestureRunner {
    public interface Check { void run() throws Exception; }
    public interface Driver { void submit(Segment segment, Receipt receipt) throws Exception; }
    public interface Pause { void run(long millis) throws Exception; }

    public static final class Segment {
        public final int fromX, fromY, x, y;
        public final long millis;
        public final boolean continuation, keepsDown, cleanup;
        Segment(int fromX, int fromY, int x, int y, long millis, boolean continuation, boolean keepsDown) {
            this(fromX, fromY, x, y, millis, continuation, keepsDown, false);
        }
        Segment(int fromX, int fromY, int x, int y, long millis, boolean continuation, boolean keepsDown, boolean cleanup) {
            this.fromX = fromX; this.fromY = fromY; this.x = x; this.y = y;
            this.millis = millis; this.continuation = continuation; this.keepsDown = keepsDown;
            this.cleanup = cleanup;
        }
    }

    /** 主线程排队任务过期后不可再发送；迟到回调只更新自己的回执，不会启动下一动作。 */
    public static final class Receipt {
        private boolean started, expired;
        private int result; // 0 等待，1 完成，2 拒绝，3 取消或未知
        private String rejection = "无障碍服务拒绝手势";
        public synchronized boolean start() {
            if (expired || started || result != 0) return false;
            started = true;
            return true;
        }
        public synchronized void complete() { finish(1); }
        public synchronized void reject() { finish(2); }
        public synchronized void reject(String reason) { rejection = reason; finish(2); }
        public synchronized void cancel() { finish(3); }
        private void finish(int value) { if (!expired && result == 0) { result = value; notifyAll(); } }
        synchronized int result() { return result; }
        synchronized String rejection() { return rejection; }
        synchronized boolean expire() { expired = true; return started; }
        synchronized int expirePending() {
            if (result != 0) return 0;
            expired = true;
            return started ? 1 : -1;
        }
        synchronized void pause(long ms) throws InterruptedException { if (result == 0) wait(ms); }
    }

    private final Driver driver;
    private final Check check;
    private final long callbackGraceMs;
    private boolean held, unknown, interrupted;
    private int heldX, heldY;

    public AccessibleGestureRunner(Driver driver, Check check) { this(driver, check, 1500); }
    public AccessibleGestureRunner(Driver driver, Check check, long callbackGraceMs) {
        this.driver = driver; this.check = check; this.callbackGraceMs = callbackGraceMs;
    }

    public void perform(TouchAction action, Pause pause) throws Exception {
        if (!action.walk) {
            hold(action.cx, action.cy, action.cx, action.cy, action.durationMs);
            return;
        }
        hold(action.cx, action.cy, action.left, action.cy, action.durationMs);
        pause.run(WalkPlan.PAUSE_BETWEEN_MS);
        hold(action.cx, action.cy, action.right, action.cy, action.durationMs * 2);
        pause.run(WalkPlan.PAUSE_BETWEEN_MS);
        hold(action.cx, action.cy, action.left, action.cy, action.durationMs);
        if (action.jump) {
            pause.run(WalkPlan.PAUSE_BEFORE_JUMP_MS);
            // 无障碍的回执失效也可能表示服务断开，不把跳跃失败吞掉。
            hold(action.jx, action.jy, action.jx, action.jy, action.jumpMs);
        }
    }

    public void hold(int cx, int cy, int x, int y, long millis) throws Exception {
        if (millis < 1 || millis > 20_000) throw new IllegalArgumentException("按压时长越界");
        Exception primary = null;
        try {
            check.run();
            long first = Math.min(millis, cx == x && cy == y ? 150 : 30);
            send(new Segment(cx, cy, x, y, first, false, millis > first), false);
            long remaining = millis - first;
            while (remaining > 0) {
                check.run();
                long part = Math.min(150, remaining);
                send(new Segment(x, y, x, y, part, true, remaining > part), false);
                remaining -= part;
            }
            check.run();
        } catch (Exception e) {
            primary = e;
            throw e;
        } finally {
            // 只在已知续接端点收尾；不能在不确定的位置补一次新的点击。
            if (held && !unknown) {
                try { send(new Segment(heldX, heldY, heldX, heldY, 1, true, false, true), true); }
                catch (Exception e) {
                    GestureSequence.ReleaseFailedException failure = new GestureSequence.ReleaseFailedException(
                            "无障碍手势未确认释放，请检查游戏触摸状态", e);
                    if (primary != null) primary.addSuppressed(failure); else throw failure;
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void send(Segment segment, boolean cleanup) throws Exception {
        Receipt receipt = new Receipt();
        try { driver.submit(segment, receipt); }
        catch (Exception e) {
            if (receipt.expire()) {
                unknown = true;
                throw new GestureSequence.ReleaseFailedException("无障碍提交结果未知", e);
            }
            throw e;
        }
        long deadline = System.nanoTime() + (segment.millis + callbackGraceMs) * 1_000_000L;
        Exception pending = null;
        while (receipt.result() == 0) {
            if (!cleanup && pending == null) {
                try { check.run(); } catch (Exception e) { pending = e; }
            }
            if (System.nanoTime() >= deadline) {
                int expiry = receipt.expirePending();
                if (expiry == 0) continue;
                if (expiry > 0) {
                    unknown = true;
                    throw new GestureSequence.ReleaseFailedException("无障碍手势回执超时，触摸状态未知", pending);
                }
                throw new IllegalStateException("无障碍服务响应超时，手势未发送", pending);
            }
            try { receipt.pause(25); }
            catch (InterruptedException e) {
                interrupted = true;
                if (!cleanup && pending == null) pending = e;
            }
        }
        if (receipt.result() == 3) {
            unknown = true;
            throw new GestureSequence.ReleaseFailedException("无障碍手势被取消，未确认释放", pending);
        }
        if (receipt.result() == 2) {
            if (pending != null) throw pending;
            throw new IllegalStateException(receipt.rejection());
        }
        held = segment.keepsDown;
        heldX = segment.x; heldY = segment.y;
        if (pending != null) throw pending;
    }
}
