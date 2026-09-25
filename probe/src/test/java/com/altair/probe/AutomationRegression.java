package com.altair.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 无 Android 设备的确定性回归测试：测试生产时序、调度器和动作互斥。 */
public final class AutomationRegression {
    static final class Event {
        final int action, x, y;
        final long down, time;
        Event(int action, long down, long time, int x, int y) {
            this.action = action; this.down = down; this.time = time; this.x = x; this.y = y;
        }
    }
    static final class Rig implements GestureSequence.Clock, GestureSequence.Input {
        long now = 1000, cancelAt = Long.MAX_VALUE;
        int failMoveAt = -1, moves;
        final List<Event> events = new ArrayList<>();
        final GestureSequence sequence = new GestureSequence(this, this, () -> now >= cancelAt);
        public long now() { return now; }
        public void sleep(long ms) { now += ms; }
        public void send(int action, long down, long time, int x, int y) throws Exception {
            events.add(new Event(action, down, time, x, y));
            if (action == GestureSequence.MOVE && ++moves == failMoveAt) throw new Exception("injection failed");
        }
        List<Event> of(int action) {
            List<Event> result = new ArrayList<>();
            for (Event event : events) if (event.action == action) result.add(event);
            return result;
        }
        void walk() throws Exception { sequence.walk(100, 500, 40, 160, 900, 500, 600, 240); }
        void walkOnly() throws Exception { sequence.walkOnly(100, 500, 40, 160, 600); }
    }
    static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    static void sequence() throws Exception {
        Rig rig = new Rig();
        rig.walk();
        List<Event> down = rig.of(0), up = rig.of(1);
        check(down.size() == 4 && up.size() == 4, "三段走位，只跳一次");
        long[] durations = {600, 1200, 600, 240};
        int[] x = {40, 160, 40, 900};
        for (int i = 0; i < 4; i++) {
            check(up.get(i).time - down.get(i).time == durations[i], "设置的实际按压时间");
            check(up.get(i).x == x[i], "左、右、左、跳顺序");
            for (Event e : rig.events) {
                if (e.time >= down.get(i).time && e.time <= up.get(i).time)
                    check(e.down == down.get(i).down, "同一手势必须保持 downTime");
            }
        }
        check(down.get(3).time - up.get(2).time == 1000, "最终松手后精确等待 1 秒");
        check(up.get(1).time - down.get(1).time == 2 * (up.get(0).time - down.get(0).time), "右段为双倍");
    }
    static void cancellation() throws Exception {
        // 三段保持、两次段间等待、最后1秒等待以及跳跃，都必须支持取消。
        for (long at : new long[]{1000, 1100, 1700, 2000, 3100, 3300, 3900, 4900}) {
            Rig rig = new Rig(); rig.cancelAt = at;
            try { rig.walk(); throw new AssertionError("取消后不应成功"); }
            catch (InterruptedException expected) { }
            check(rig.of(0).size() == rig.of(1).size(), "取消时释放全部按下事件");
            for (Event e : rig.of(0)) check(e.time < at, "取消后不得新按下或跳跃");
        }
    }
    static void failure() throws Exception {
        Rig rig = new Rig(); rig.failMoveAt = 5;
        try { rig.walk(); throw new AssertionError("注入失败不应显示成功"); }
        catch (Exception expected) { check("injection failed".equals(expected.getMessage()), "保留失败原因"); }
        check(rig.of(0).size() == 1 && rig.of(1).size() == 1, "失败后松手，不执行右段或跳跃");
    }

    /**
     * 跳跃是**可选**项：未标记时只走三段，不跳、也不多等那 1 秒。
     * 这条路径以前根本不存在 —— 代码把跳跃当必需项，缺了就整次走位判失败。
     */
    static void walkWithoutJump() throws Exception {
        Rig rig = new Rig();
        rig.walkOnly();
        List<Event> down = rig.of(0), up = rig.of(1);
        check(down.size() == 3 && up.size() == 3, "未标跳跃时只走三段、不按第四次");
        long[] durations = {600, 1200, 600};
        int[] x = {40, 160, 40};
        for (int i = 0; i < 3; i++) {
            check(up.get(i).time - down.get(i).time == durations[i], "三段按压时长仍为 1:2:1");
            check(up.get(i).x == x[i], "左、右、左顺序");
        }
    }

    static void walkOnlyCancellation() throws Exception {
        for (long at : new long[]{1000, 1100, 1700, 2000, 3100, 3300}) {
            Rig rig = new Rig(); rig.cancelAt = at;
            try { rig.walkOnly(); throw new AssertionError("取消后不应成功"); }
            catch (InterruptedException expected) { }
            check(rig.of(0).size() == rig.of(1).size(), "取消时释放全部按下事件");
            for (Event e : rig.of(0)) check(e.time < at, "取消后不得新按下");
        }
    }

    /**
     * 闸门契约：单次注入必须走 INJECT 档，整段走位才走 WALK 档。
     *
     * ★ 回归的是"bind 时把 mode 丢掉、两档都按 WALK 执行"这个真实 bug ——
     * 它的现象是每补一个技能，悬浮面板就被挪走再放回一次。
     */
    static void injectGate() {
        final String[] seenOn = new String[1], seenOff = new String[1];
        final int[] counts = {0, 0};
        final boolean[] ran = {false};
        InjectShield.INSTANCE.bind((mode, on, l, t, r, b) -> {
            if (on) { seenOn[0] = mode.name(); counts[0]++; }
            else { seenOff[0] = mode.name(); counts[1]++; }
            return kotlin.Unit.INSTANCE;
        });
        try {
            InjectShield.INSTANCE.aroundInject(1, 2, 3, 4, () -> { ran[0] = true; return "ok"; });
            check("INJECT".equals(seenOn[0]) && "INJECT".equals(seenOff[0]), "单次注入用 INJECT 档");
            check(ran[0] && counts[0] == 1 && counts[1] == 1, "闸门开合各一次");

            InjectShield.INSTANCE.aroundWalk(1, 2, 3, 4, () -> "ok");
            check("WALK".equals(seenOn[0]) && "WALK".equals(seenOff[0]), "整段走位用 WALK 档");

            // 让路失败 → 绝不注入
            ran[0] = false;
            InjectShield.INSTANCE.bind((mode, on, l, t, r, b) -> {
                if (on) throw new IllegalStateException("gate failed");
                return kotlin.Unit.INSTANCE;
            });
            try {
                InjectShield.INSTANCE.aroundWalk(0, 0, 1, 1, () -> { ran[0] = true; return "ok"; });
                throw new AssertionError("让路失败时不应注入");
            } catch (IllegalStateException expected) {
                check("gate failed".equals(expected.getMessage()), "保留让路失败原因");
            }
            check(!ran[0], "让路失败后注入块不得执行");

            // 注入块抛异常 → 仍必须恢复覆盖层，且原始异常不能被吞掉
            final boolean[] restored = {false};
            InjectShield.INSTANCE.bind((mode, on, l, t, r, b) -> {
                if (!on) restored[0] = true;
                return kotlin.Unit.INSTANCE;
            });
            try {
                InjectShield.INSTANCE.aroundWalk(0, 0, 1, 1, () -> {
                    throw new IllegalStateException("boom");
                });
                throw new AssertionError("异常应向外传播");
            } catch (IllegalStateException expected) {
                check("boom".equals(expected.getMessage()), "保留注入异常");
            }
            check(restored[0], "注入失败也必须恢复覆盖层");
        } finally {
            InjectShield.INSTANCE.bind(null);
        }
    }

    /**
     * 屏幕记录的三选一判定。
     *
     * ★ 回归的是"点启动立马停止"那个 bug：当时把「没有记录」当成「记录不匹配」，
     * 于是所有旧版标记的使用者一升级就被拦在启动之外，而屏幕其实从没变过。
     */
    static void screenMatch() {
        Picks p = Picks.INSTANCE;
        check(p.screenMatch("1280,720,0", "1280,720,0") == Picks.ScreenMatch.OK, "记录一致 → 直接用");
        check(p.screenMatch(null, "1280,720,0") == Picks.ScreenMatch.ADOPT,
            "没有记录（旧版标记）→ 按当前屏幕补记，不能拦住启动");
        check(p.screenMatch("720,1280,0", "1280,720,0") == Picks.ScreenMatch.STALE, "方向变了 → 必须重标");
        check(p.screenMatch("1280,720,0", "1280,720,1") == Picks.ScreenMatch.STALE, "旋转变了 → 必须重标");
        check(p.screenMatch("2340,1080,0", "1280,720,0") == Picks.ScreenMatch.STALE, "分辨率变了 → 必须重标");
    }

    static void scheduling() {
        Schedule s = new Schedule();
        s.configure(280_000, 1000);
        check(s.ready(1000), "首次立即执行");
        s.success(1000);
        check(!s.ready(264_200) && s.ready(281_000), "按280秒而不是94%排期");
        s.configure(600_000, 2000);
        check(s.dueAt() == 601_000, "修改间隔从上次成功重新排期");
        s.configure(0, 3000);
        check(!s.ready(Long.MAX_VALUE), "关闭技能后不执行");
        s.configure(10_000, 4000);
        check(s.ready(4000), "新增启用的技能必须得到排期");
        s.failure(4000);
        check(s.dueAt() == 34_000 && s.failures == 1, "失败退避");
        s.success(35_000);
        check(s.failures == 0 && s.dueAt() == 45_000, "成功后重置连续失败");
    }
    static void actions() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            kotlin.Pair<Boolean, String> result = Actions.INSTANCE.run("first", token -> {
                token.attach(() -> { cancelled.set(true); release.countDown(); return kotlin.Unit.INSTANCE; });
                entered.countDown();
                try { check(release.await(2, TimeUnit.SECONDS), "取消回调必须立即触发"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return kotlin.Unit.INSTANCE;
            });
            check(!result.getFirst(), "取消必须返回失败状态");
        });
        worker.setUncaughtExceptionHandler((t, e) -> { throwables.add(e); });
        worker.start();
        check(entered.await(2, TimeUnit.SECONDS), "动作启动");
        kotlin.Pair<Boolean, String> busy = Actions.INSTANCE.run("second", token -> {
            throw new AssertionError("并行动作不得插入");
        });
        check(!busy.getFirst(), "互斥拒绝并行动作");
        Actions.INSTANCE.cancel();
        worker.join(2000);
        check(cancelled.get() && !worker.isAlive() && !Actions.INSTANCE.getBusy(), "取消后释放动作锁");
        check(throwables.isEmpty(), "后台动作断言通过");
        check(Actions.INSTANCE.run("restart", token -> kotlin.Unit.INSTANCE).getFirst(), "取消后可以重新启动");
        Actions.Token early = new Actions.Token();
        early.cancel();
        AtomicBoolean observed = new AtomicBoolean();
        early.attach(() -> { observed.set(true); return kotlin.Unit.INSTANCE; });
        check(observed.get(), "启动前取消不能丢失");
    }
    static final List<Throwable> throwables = new java.util.concurrent.CopyOnWriteArrayList<>();
    static void shell() throws Exception {
        // 只执行测试 shell，不调用主机 su，也不需要 Root。
        java.util.concurrent.atomic.AtomicReference<Process> active = new java.util.concurrent.atomic.AtomicReference<>();
        RootShell shell = new RootShell(command -> {
            try {
                Process p = new ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start();
                active.set(p);
                return p;
            } catch (java.io.IOException e) { throw new RuntimeException(e); }
        });
        check(shell.execute("printf good", 1000).getOk(), "退出码0成功");
        RootShell.Result failed = shell.execute("printf bad >&2; exit 7", 1000);
        check(!failed.getOk() && failed.getExitCode() == 7 && failed.getOutput().contains("bad"), "保留退出码及stderr");
        RootShell.Result timeout = shell.execute("exec sleep 5", 30);
        check(timeout.getTimedOut() && !timeout.getOk(), "超时不能伪装成功");
        String path = "x' y; $(printf injected)";
        check(shell.execute("printf %s " + RootShell.Companion.quote(path), 1000).getOutput().equals(path), "shell参数转义");
        CountDownLatch launched = new CountDownLatch(1);
        RootShell cancellable = new RootShell(command -> {
            try {
                Process p = new ProcessBuilder("/bin/sh", "-c", command).start();
                active.set(p); launched.countDown(); return p;
            } catch (java.io.IOException e) { throw new RuntimeException(e); }
        });
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread waiting = new Thread(() -> {
            try { cancellable.execute("exec sleep 5", 10_000); }
            catch (Exception e) { interrupted.set(e instanceof InterruptedException); }
        });
        waiting.start();
        check(launched.await(2, TimeUnit.SECONDS), "测试进程已启动");
        waiting.interrupt(); waiting.join(2000);
        active.get().waitFor(1, TimeUnit.SECONDS);
        check(interrupted.get() && !waiting.isAlive() && !active.get().isAlive(), "中断清理命令进程");
    }
    public static void main(String[] args) throws Exception {
        sequence(); cancellation(); failure();
        walkWithoutJump(); walkOnlyCancellation(); injectGate();
        screenMatch();
        scheduling(); actions(); shell();
        System.out.println(
            "PASS: 1:2:1 时序、1秒后单次跳跃、可选跳跃只走三段、按压设置、8+6 个取消阶段、" +
                "失败松手、闸门两档与失败禁止注入、屏幕记录三选一、独立排期、互斥重启、" +
                "命令退出码/超时/中断/转义"
        );
    }
}
