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
    static class Rig implements GestureSequence.Clock, GestureSequence.Input {
        long now = 1000, cancelAt = Long.MAX_VALUE;
        /**
         * 从第 N 次 MOVE 起**持续**失败（0 = 不启用）。
         *
         * 必须是"持续"而不是"只失败那一次"：注入层现在会对单个事件重试，
         * 只失败一次会被重试救回来 —— 那正是它该做的事。
         */
        int failMovesFrom = 0, moves;
        /** 开头的 N 次发送直接失败，用来验证重试。 */
        int rejectFirst = 0, sent;
        /** 所有 UP 都失败：验证松手失败不会盖掉真正的失败原因。 */
        boolean failUps = false;
        /** 在这个坐标上的 DOWN 一律被拒（Integer.MIN_VALUE = 不启用）。 */
        int refuseDownX = Integer.MIN_VALUE, refuseDownY = Integer.MIN_VALUE;
        final List<Event> events = new ArrayList<>();
        final List<String> notices = new ArrayList<>();
        final GestureSequence sequence =
            new GestureSequence(this, this, () -> now >= cancelAt, notices::add);
        public long now() { return now; }
        public void sleep(long ms) { now += ms; }
        public void send(int action, long down, long time, int x, int y) throws Exception {
            if (action == GestureSequence.UP && failUps) throw new Exception("release refused");
            if (action == GestureSequence.DOWN && x == refuseDownX && y == refuseDownY) {
                throw new Exception("injection refused");
            }
            if (++sent <= rejectFirst) throw new Exception("injection refused");
            events.add(new Event(action, down, time, x, y));
            if (action == GestureSequence.MOVE) {
                moves++;
                if (failMovesFrom > 0 && moves >= failMovesFrom) throw new Exception("injection failed");
            }
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
        Rig rig = new Rig(); rig.failMovesFrom = 5;
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

    /**
     * 注入被系统丢弃时要重试。
     *
     * ★ 回归的是实机日志里的 `系统拒绝触摸注入`：InputDispatcher 在窗口切换/卡顿时会丢弃
     * 单次注入并返回 false。v0.26.4 用 `input swipe` 子进程从不检查送达，所以这种丢包一直
     * 是静默的；改成检查返回值之后第一次变成硬失败，表现为"三段走位都成功，只有最后那一跳
     * 被拒，于是整轮算失败"。
     */
    static void injectionRetry() throws Exception {
        // 开头两次发送被拒：重试应当救回来，且落点与一次成功完全一致
        Rig retried = new Rig();
        retried.rejectFirst = 2;
        retried.walkOnly();
        check(retried.of(0).size() == 3 && retried.of(1).size() == 3, "重试后三段仍然完整");

        Rig clean = new Rig();
        clean.walkOnly();
        for (int i = 0; i < 3; i++) {
            check(retried.of(0).get(i).x == clean.of(0).get(i).x, "重试不改变推杆落点");
        }
    }

    /** 按下本身就被拒时，绝不能补发一个 UP —— 那等于朝没按下的手指发释放事件。 */
    static void noReleaseWithoutPress() throws Exception {
        Rig rig = new Rig();
        rig.rejectFirst = 3;                       // DOWN 的 3 次尝试全部失败
        try {
            rig.sequence.hold(100, 500, 100, 500, 600);
            throw new AssertionError("按下失败应当抛出");
        } catch (Exception expected) {
            check("injection refused".equals(expected.getMessage()), "保留失败原因");
        }
        check(rig.of(0).isEmpty() && rig.of(1).isEmpty(), "没按下过就不该有 DOWN/UP");
    }

    /**
     * 三段走位成功、只有收尾的跳跃被拒 —— 这一轮走位仍然算成功。
     *
     * 跳跃坐标本来就是可选项，把它算成失败会让"人已经走完一轮"的战果被一个装饰动作抹掉，
     * 还会连锁触发引擎熔断把任务停掉（实机日志里正是这个现象）。
     */
    static void jumpFailureIsNotFatal() throws Exception {
        Rig rig = new Rig();
        // 只让跳跃那一下的 DOWN 被拒：跳跃落在 (900,500)，三段走位的 DOWN 都在 (100,500)。
        rig.refuseDownX = 900;
        rig.refuseDownY = 500;
        rig.walk();                                 // 不应抛出
        check(rig.of(0).size() == 3, "三段走位照常完成");
        check(rig.of(1).size() == 3, "三段走位都松了手");
        check(rig.notices.size() == 1 && rig.notices.get(0).startsWith("TOUCH_JUMP_FAILED"),
            "跳跃失败要留下可查的提示，而不是无声无息");
    }

    /**
     * 松手失败不能盖掉真正的失败原因。
     *
     * 松手在 `finally` 里执行；那里再抛异常的话，排查时看到的就是 UP 的错误，
     * 而真正的问题（这里是 MOVE 注入失败）被吞掉了 —— 日志会指向完全错误的方向。
     */
    static void releaseFailureDoesNotMask() throws Exception {
        Rig rig = new Rig();
        rig.failMovesFrom = 5;
        rig.failUps = true;
        try {
            rig.walk();
            throw new AssertionError("MOVE 注入失败应当抛错");
        } catch (Exception expected) {
            check("injection failed".equals(expected.getMessage()),
                "报出的必须是 MOVE 的真实原因，而不是松手失败");
        }
    }

    /**
     * 动作之间的间隔规则。
     *
     * ★ 回归的是"注入成功但游戏没接受"：游戏要放完上一个动作的动画才接受下一次输入，
     * 而注入层只知道事件送到了。没有间隔时日志一切正常、游戏里什么都没发生。
     */
    static void actionPacing() {
        ActionPacer pacer = new ActionPacer(1000, 1000);

        // 第一个动作不受约束
        check(pacer.delayBefore(ActionPacer.BUFF, 5000) == 0, "首个动作立即执行");

        pacer.done(ActionPacer.BUFF, 5000);
        check(pacer.delayBefore(ActionPacer.BUFF, 5000) == 1000, "紧接着再补要等满间隔");
        check(pacer.delayBefore(ActionPacer.BUFF, 5500) == 500, "只等到差额");
        check(pacer.delayBefore(ActionPacer.BUFF, 6000) == 0, "等满后不再等待");
        check(pacer.delayBefore(ActionPacer.BUFF, 9000) == 0, "超时后不等待");

        // 换类（补 BUFF ↔ 走位）走另一档间隔
        check(pacer.delayBefore(ActionPacer.WALK, 5900) == 100, "补 BUFF 后走位要等换类间隔");
        check(pacer.delayBefore(ActionPacer.WALK, 6000) == 0, "等满换类间隔");

        pacer.done(ActionPacer.WALK, 7000);
        check(pacer.delayBefore(ActionPacer.BUFF, 7000) == 1000, "走位后补 BUFF 同样要等");
        check(pacer.delayBefore(ActionPacer.BUFF, 8000) == 0, "等满即可补");

        // 两档可以不同：换类留得更足
        ActionPacer wider = new ActionPacer(1000, 1500);
        wider.done(ActionPacer.BUFF, 0);
        check(wider.delayBefore(ActionPacer.BUFF, 0) == 1000, "同类用同类间隔");
        check(wider.delayBefore(ActionPacer.WALK, 0) == 1500, "换类用换类间隔");

        check(wider.sameKindGapMs() == 1000 && wider.switchKindGapMs() == 1500, "间隔可读");
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
        // 原地走位开关关掉时靠这一条：引擎把走位周期配成 0，界面读到 dueAt()==0 就显示「—」，
        // 而不是把 0 当成"立刻就要走"、倒计时成 00:00。
        check(s.dueAt() == 0, "周期 0 时 dueAt 必须为 0（界面据此显示「—」）");
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
        injectionRetry(); noReleaseWithoutPress(); jumpFailureIsNotFatal();
        releaseFailureDoesNotMask();
        screenMatch(); actionPacing();
        scheduling(); actions(); shell();
        System.out.println(
            "PASS: 1:2:1 时序、1秒后单次跳跃、可选跳跃只走三段、按压设置、8+6 个取消阶段、" +
                "失败松手、闸门两档与失败禁止注入、屏幕记录三选一、注入重试、未按下不松手、" +
                "跳跃失败不致命、动作间隔两类、独立排期、互斥重启、命令退出码/超时/中断/转义"
        );
    }
}
