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
        /** 所有 UP 都失败：验证松手失败会阻止成功，并且不盖掉真正的动作错误。 */
        boolean failUps = false;
        int upAttempts = 0;
        /** 在这个坐标上的 DOWN 一律被拒（Integer.MIN_VALUE = 不启用）。 */
        int refuseDownX = Integer.MIN_VALUE, refuseDownY = Integer.MIN_VALUE;
        final List<Event> events = new ArrayList<>();
        final List<String> notices = new ArrayList<>();
        final GestureSequence sequence =
            new GestureSequence(this, this, () -> now >= cancelAt, notices::add);
        public long now() { return now; }
        public void sleep(long ms) { now += ms; }
        public void send(int action, long down, long time, int x, int y) throws Exception {
            if (action == GestureSequence.UP) upAttempts++;
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
    @SuppressWarnings("unchecked")
    static <E extends Throwable> kotlin.Unit raise(Throwable e) throws E { throw (E) e; }

    static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    /**
     * 断言一段代码必须抛出 [IllegalArgumentException]，并检查它说清了原因。
     *
     * 坐标校验是"宁可拒绝也不打错位置"的最后一道闸，所以它的拒绝理由必须可读 ——
     * 用户看到的是这句话，不是堆栈。
     */
    static void expectRejected(String mustContain, Runnable body) {
        try {
            body.run();
        } catch (IllegalArgumentException rejected) {
            String message = String.valueOf(rejected.getMessage());
            check(message.contains(mustContain),
                "拒绝理由要说清'" + mustContain + "'，实际：" + message);
            return;
        }
        throw new AssertionError("这组坐标必须被拒绝，实际通过了");
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

            // 未绑定处理器时默认 fail-closed；不能静默跳过覆盖层让路。
            InjectShield.INSTANCE.bind(null);
            ran[0] = false;
            try {
                InjectShield.INSTANCE.aroundInject(0, 0, 1, 1, () -> { ran[0] = true; return "ok"; });
                throw new AssertionError("handler 未绑定时不应继续注入");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().contains("尚未就绪"), "给出让路尚未就绪的诊断");
            }
            check(!ran[0], "未绑定 handler 时注入块不得执行");

            // 只有显式允许时，才可在没有悬浮窗 adapter 的场景运行 no-op。
            InjectShield.INSTANCE.bind(null, true);
            check("ok".equals(InjectShield.INSTANCE.aroundInject(0, 0, 1, 1, () -> "ok")),
                "显式允许无 handler 时可走 no-op adapter");
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
    static void safeScreenBounds() {
        ScreenBounds bounds = ScreenBounds.from(1280, 720, 12, 32, 14, 24);
        check(bounds.left == 12 && bounds.top == 32 && bounds.right == 1266 && bounds.bottom == 696,
            "保持1280x720游戏坐标但扣除上下系统栏");
        check(bounds.clampX(-20, 100) == 12, "窗口左偏移越界恢复到安全区原点");
        check(bounds.clampX(2000, 100) == 1166, "窗口右边越界恢复到安全区");
        check(bounds.clampY(-20, 80) == 32, "窗口不能拖进状态栏区域");
        check(bounds.clampY(1000, 80) == 616, "窗口底部不越出安全区");
        check(bounds.defaultRightX(56, 12) == 1198 && bounds.defaultTopY(12, 56) == 44,
            "1280x720 下悬浮球默认贴近右侧并避开系统栏");
        ScreenBounds full = ScreenBounds.from(1280, 720, 0, 0, 0, 0);
        check(full.clampY(700, 80) == 640, "无系统栏/横屏旧设备仍按屏幕底边夹紧");
    }

    static void settingsConflictPolicy() {
        check(!ActionGatePolicy.hasConflict(true, true, false), "外部值未变时本页显式选择优先");
        check(!ActionGatePolicy.hasConflict(true, false, false), "双方选择相同新值时无冲突");
        check(ActionGatePolicy.hasConflict(true, false, true), "本地只修改其他设置时不覆盖外部新值");
        check(ActionGatePolicy.hasConflict(false, true, false), "反向变更也保护外部保存值");
    }

    static void signerTrust() {
        String expected = "d32bc8a79cd521effe61cf0cf7081b73ffdaf76131337c4bd0cb8008e0244167";
        check(ApkSignerTrust.accepts(expected,
            java.util.Set.of("D32BC8A79CD521EFFE61CF0CF7081B73FFDAF76131337C4BD0CB8008E0244167")),
            "签名摘要比较不区分十六进制大小写");
        check(!ApkSignerTrust.accepts(expected,
            java.util.Set.of("00", expected)), "未确认轮换策略前不接受额外 signer");
        check(!ApkSignerTrust.accepts(expected, java.util.Set.of("00")), "拒绝未知 signer");
        check(!ApkSignerTrust.accepts("short", java.util.Set.of(expected)), "拒绝格式错误的 trust anchor");
        check(!ApkSignerTrust.accepts(expected, null), "无签名摘要集合时拒绝 APK");
    }

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
            check(expected.getSuppressed().length == 1 &&
                    expected.getSuppressed()[0] instanceof GestureSequence.ReleaseFailedException,
                "松手失败必须作为 suppressed 错误保留");
        }
        check(rig.upAttempts == 3, "动作失败后仍有限重试三次松手");
    }

    /** 正常路径 UP 未确认时不能报告动作成功；取消期间仍必须尝试松手。 */
    static void releaseFailureIsFatal() throws Exception {
        Rig rig = new Rig();
        rig.failUps = true;
        try {
            rig.walkOnly();
            throw new AssertionError("未确认松手不得报告走位成功");
        } catch (GestureSequence.ReleaseFailedException expected) {
            check(expected.getMessage().contains("UP(40,500)"), "指出未确认的松手坐标");
        }
        check(rig.upAttempts == 3, "UP 失败后有限重试三次");

        Rig cancelled = new Rig();
        cancelled.failUps = true;
        cancelled.cancelAt = 1100;
        try {
            cancelled.walkOnly();
            throw new AssertionError("取消仍应向上传递");
        } catch (InterruptedException expected) {
            check(expected.getSuppressed().length == 1 &&
                    expected.getSuppressed()[0] instanceof GestureSequence.ReleaseFailedException,
                "取消不能掩盖无法释放手势的安全错误");
        }
        check(cancelled.upAttempts == 3, "取消时仍有限重试松手");
    }

    /**
     * 失败分类：只有"未确认松手"才配得上"触摸状态未知"。
     *
     * ★ 回归的是实测里那句"未确认松手，但走位正常返回"：当时 [GestureSequence] 已经把释放
     * 失败挂成 suppressed，但动作层只返回 `false + 文案`，类别信息到此为止 ——
     * 于是引擎把它当成一次普通失败，照常开始下一轮注入。
     */
    static void failureClassification() {
        InterruptedException cancelled = new InterruptedException("cancelled");
        cancelled.addSuppressed(new GestureSequence.ReleaseFailedException("UP failed", null));
        check(ActionFailure.from(cancelled, null).isReleaseUnknown(), "取消不能覆盖松手失败");
        Exception cycle = new Exception("cycle");
        Exception other = new Exception("other");
        cycle.addSuppressed(other);
        other.addSuppressed(cycle);
        check(!ActionFailure.carriesReleaseFailure(cycle), "异常链成环不能无限递归");
        // 直接就是释放失败
        ActionFailure direct = ActionFailure.from(
            new GestureSequence.ReleaseFailedException("系统未确认触摸松手 UP(40,500)", null), null);
        check(direct.isReleaseUnknown(), "ReleaseFailedException → 触摸状态未知");

        // ★ 主错误是 MOVE 注入失败，释放失败只能搭车挂在 suppressed 上（v0.28.6 的真实形态）
        Exception move = new Exception("injection failed");
        move.addSuppressed(new GestureSequence.ReleaseFailedException("系统未确认触摸松手 UP(40,500)", null));
        check(ActionFailure.from(move, null).isReleaseUnknown(),
            "suppressed 里的释放失败同样必须判为触摸状态未知");

        // 嵌套一层包装也要认出来
        Exception wrapped = new IllegalStateException("输入未完成", move);
        check(ActionFailure.from(wrapped, null).isReleaseUnknown(), "cause 链里的释放失败也要认出来");

        check(ActionFailure.from(new Exception("injection refused"), null).fault == ActionFailure.Fault.FAILED,
            "普通注入失败仍是普通失败");
        check(ActionFailure.from(new InterruptedException("动作已取消"), null).isCancelled(), "取消单独一类");

        RunResult failure = RunResult.failure(12L, direct);
        check(failure.isDangerous() && !failure.isOk(), "危险结局必须能被调用方一眼认出");
        check(failure.describe("走位与跳跃").contains("未确认松手"), "文案要说清发生了什么");
        check(RunResult.success(30L, "输入已完成").describe("技能1").contains("输入已完成"),
            "成功只能说输入完成，不能声称游戏里已经生效");
    }

    static void jumpMoveAndReleaseFailure() throws Exception {
        Rig rig = new Rig() {
            @Override public void send(int action, long down, long time, int x, int y) throws Exception {
                if (x == 900 && (action == GestureSequence.MOVE || action == GestureSequence.UP))
                    throw new Exception(action == GestureSequence.MOVE ? "jump MOVE failed" : "jump UP failed");
                super.send(action, down, time, x, y);
            }
        };
        Actions.INSTANCE.getGate().clear();
        RunResult result = Actions.INSTANCE.run("组合失败", token -> {
            try { rig.walk(); return kotlin.Unit.INSTANCE; }
            catch (Exception e) { return raise(e); }
        });
        check(result.isDangerous() && Actions.INSTANCE.getGate().getLocked(),
            "跳跃 MOVE 与 UP 同时失败不能正常返回，也不能继续注入");
        Actions.INSTANCE.getGate().clear();
    }

    /**
     * 未确认松手 → 拉闸 → 后续注入在**入口**就被挡住，直到用户确认。
     *
     * 这是 P0 的核心验收：危险结局不能报成功，也不能继续下一次自动动作，
     * 而停止入口（[Actions.cancel]）始终可用。
     */
    static void injectGateLatches() throws Exception {
        Actions actions = Actions.INSTANCE;
        InjectGate gate = actions.getGate();
        gate.clear();

        // ① 普通失败不拉闸：否则任何一次注入被拒都会把用户锁在外面
        RunResult plain = actions.run("普通失败", token -> {
            throw new IllegalStateException("injection refused");
        });
        check(!plain.isOk() && !plain.isDangerous(), "普通失败不算危险结局");
        check(!gate.getLocked(), "普通失败不能拉闸");

        // ② 释放失败搭在主错误的 suppressed 上（真实形态）→ 必须拉闸
        RunResult released = actions.run("未确认松手", token -> {
            Exception move = new Exception("injection failed");
            move.addSuppressed(new GestureSequence.ReleaseFailedException("系统未确认触摸松手 UP(40,500)", null));
            return raise(move);
        });
        check(released.isDangerous(), "未确认松手是危险结局");
        check(gate.getLocked(), "未确认松手必须拉闸");

        // ③ 拉闸后：动作**没有执行**，且返回 blocked 而不是普通失败
        AtomicBoolean ran = new AtomicBoolean();
        RunResult blocked = actions.run("技能1", token -> { ran.set(true); return kotlin.Unit.INSTANCE; });
        check(!ran.get(), "闸门关闭时注入动作不得执行");
        check(blocked.isBlocked() && !blocked.isDangerous(),
            "被挡住要说清是闸门挡的，而不是'触摸状态未知'（动作根本没执行）");
        check(!actions.getBusy(), "被挡住不算'正在执行'，界面才能显示解除入口");

        // ④ 取消/停止不会解锁：停止只是取消当前动作，触摸状态并没有恢复
        actions.cancel();
        check(gate.getLocked(), "停止任务不能解锁（触摸状态未知不是'用户叫停'）");
        check(actions.run("技能1", token -> { ran.set(true); return kotlin.Unit.INSTANCE; }).isBlocked(),
            "停止后仍然被挡住");

        // ⑤ 只有用户确认才解锁
        gate.clear();
        ran.set(false);
        RunResult after = actions.run("技能1", token -> { ran.set(true); return kotlin.Unit.INSTANCE; });
        check(after.isOk() && ran.get(), "解除后可以正常执行");
        check(!gate.getLocked(), "解除后闸门打开");
        gate.clear();
    }

    /** 拉闸必须覆盖"真实走位函数"和"取消路径"，而不只是一个人造 block。 */
    static void releaseFailureBlocksWalkAndKeepsStopUsable() throws Exception {
        Actions actions = Actions.INSTANCE;
        InjectGate gate = actions.getGate();
        gate.clear();

        RunResult first = actions.run("未确认松手", token -> {
            return raise(new GestureSequence.ReleaseFailedException("系统未确认触摸松手 UP(40,500)", null));
        });
        check(first.isDangerous() && gate.getLocked(), "释放失败直接拉闸");

        RunResult second = actions.run("走位与跳跃", token -> {
            throw new AssertionError("闸门关闭时走位不得执行");
        });
        check(second.isBlocked(), "闸门关闭时走位在入口就被挡住");

        // 停止入口必须始终可用：正在跑的 worker 要能被立刻打断
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        gate.clear();
        Thread worker = new Thread(() -> {
            actions.run("长动作", token -> {
                entered.countDown();
                try {
                    for (int i = 0; i < 200; i++) {
                        token.check();
                        Thread.sleep(20);
                    }
                } catch (InterruptedException e) {
                    cancelled.set(true);
                    return raise(e);
                }
                return kotlin.Unit.INSTANCE;
            });
        });
        worker.start();
        check(entered.await(2, TimeUnit.SECONDS), "长动作已开始");
        actions.cancel();
        worker.join(3000);
        check(cancelled.get() && !worker.isAlive(), "停止入口在闸门机制下仍然可用");
        check(!actions.getBusy(), "取消后释放动作锁");
        gate.clear();
    }

    /** 预算和参数必须与生产时序、TouchAgent 协议一致。 */
    static void walkBudget() throws Exception {
        long d = 600;
        Rig withoutJump = new Rig();
        long start = withoutJump.now;
        withoutJump.walkOnly();
        check(withoutJump.now - start == WalkPlan.walkOnlyBudgetMs(d), "预算必须等于生产动作的实际耗时");
        Rig withJumpRig = new Rig();
        start = withJumpRig.now;
        withJumpRig.walk();
        check(withJumpRig.now - start == WalkPlan.walkWithJumpBudgetMs(d, 240), "跳跃预算与生产时序一致");
        check(WalkPlan.walkOnlyBudgetMs(d) == 4 * d + 2 * 250,
            "三段按压是 4D（1:2:1），不是 3D；停顿也要算进去");
        check(WalkPlan.walkOnlyBudgetMs(d) > 4 * d, "预算必须大于按压本身的总时长");
        // 2 次段间停顿 + 跳跃前 1 秒 + 跳跃按压。
        check(WalkPlan.walkWithJumpBudgetMs(d, 90L) == 4 * d + 2 * 250 + 1000 + 90,
            "带跳跃的预算 = 三段 + 两段停顿 + 一次 1 秒等待 + 跳跃按压");
        check(WalkPlan.walkWithJumpBudgetMs(d, 90L) > WalkPlan.walkOnlyBudgetMs(d),
            "加一次跳跃必须加时间");

        WalkPlan.Plan noJump = WalkPlan.walkOnly(100, 500, 40, 160, d, 1280, 720);
        check(noJump.getBudgetMs() == WalkPlan.walkOnlyBudgetMs(d), "计划自带预算，调用方不必再算");
        check(!noJump.isJump() && noJump.getPoints().size() == 3, "不跳时只有三个按压点");
        check(noJump.getArgs().equals(java.util.List.of(
            WalkPlan.MODE_WALK3, "100", "500", "40", "160", "600")), "命令参数与校对过的形态一致");

        WalkPlan.Plan withJump = WalkPlan.walkWithJump(100, 500, 40, 160, 900, 500, d, 90L, 1280, 720);
        check(withJump.isJump() && withJump.getPoints().size() == 4, "跳跃坐标是本轮真实使用的第四个点");
        check(withJump.getArgs().equals(java.util.List.of(
            WalkPlan.MODE_WALK, "100", "500", "40", "160", "900", "500", "600", "90")),
            "带跳跃参数顺序与 TouchAgent 协议一致");

        int[] shield = withJump.shield(1280, 720);
        check(shield[0] == 0 && shield[1] == 500 - 48 && shield[2] == 900 + 48 && shield[3] == 500 + 48,
            "让路矩形必须包住本轮所有按压点");
        int[] edge = WalkPlan.walkOnly(10, 5, 0, 20, d, 1280, 720).shield(1280, 720);
        check(edge[0] == 0 && edge[1] == 0, "让路矩形不能越出屏幕");
        int[] farEdge = WalkPlan.walkWithJump(100, 500, 40, 160, 1279, 719, d, 90, 1280, 720).shield(1280, 720);
        check(farEdge[2] == 1280 && farEdge[3] == 720, "Rect 右下边界排他，必须包含屏幕最后一个像素");
    }

    /** 本轮真正用到的每个坐标都要校验，包括跳跃坐标和归一化越界。 */
    static void walkCoordinatesValidated() {
        WalkPlan.Plan ok = WalkPlan.walkOnly(100, 500, 40, 160, 600, 1280, 720);
        check(ok.getPoints().size() == 3, "正常计划可构造");

        expectRejected("等幅", () -> WalkPlan.walkOnly(100, 500, 40, 200, 600, 1280, 720));
        expectRejected("幅度", () -> WalkPlan.walkOnly(100, 500, 100, 100, 600, 1280, 720));
        expectRejected("超出屏幕", () -> WalkPlan.walkOnly(10, 500, -40, 60, 600, 1280, 720));
        // ★ 三段完全合法，只有跳跃坐标越界 —— 正是"跳跃是可选动作所以不用校验"的漏洞
        expectRejected("超出屏幕",
            () -> WalkPlan.walkWithJump(100, 500, 40, 160, 1500, 500, 600, 90L, 1280, 720));
        expectRejected("1280×720", () -> WalkPlan.validatePoint(1280, 700, 1280, 720, "跳跃"));
        expectRejected("0~1", () -> WalkPlan.requireNormalized(1.4f, 0.5f, "技能1"));
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

    /**
     * 补 BUFF 间隔是用户在设置页填的，改完**不必重启任务**就该生效，
     * 而且不能因为"改了数值"把"上一次动作何时结束"一起丢掉 —— 那会让下一次动作紧贴上来。
     */
    static void pacingConfigurable() {
        ActionPacer pacer = new ActionPacer(1500, 1500);
        pacer.done(ActionPacer.BUFF, 10_000);
        check(pacer.delayBefore(ActionPacer.BUFF, 10_000) == 1500, "默认 1500ms 生效");

        // 用户在挂机途中把间隔调大到 3000：数值立刻生效，已记录的状态要保留
        pacer.setSameKindGapMs(3000);
        check(pacer.sameKindGapMs() == 3000, "新数值已生效");
        check(pacer.delayBefore(ActionPacer.BUFF, 10_000) == 3000,
            "改大后立刻按新值等待（而不是重头开始）");
        check(pacer.delayBefore(ActionPacer.BUFF, 11_500) == 1500,
            "仍按上一次结束时刻算差额，说明状态没被丢掉");

        // 换类间隔跟着补 BUFF 间隔走，但不低于下限
        check(ActionPacer.switchGapFor(1500, 1000) == 1500, "补 BUFF 间隔 1500 → 换类也 1500");
        check(ActionPacer.switchGapFor(3000, 1000) == 3000, "调大到 3000 → 换类跟着上去");
        check(ActionPacer.switchGapFor(300, 1000) == 1000, "调小到 300 → 换类守住 1000 下限");

        try {
            pacer.setSameKindGapMs(-1);
            throw new AssertionError("负间隔应当被拒");
        } catch (IllegalArgumentException expected) { }
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
            RunResult result = Actions.INSTANCE.run("first", token -> {
                token.attach(() -> { cancelled.set(true); release.countDown(); return kotlin.Unit.INSTANCE; });
                entered.countDown();
                try { check(release.await(2, TimeUnit.SECONDS), "取消回调必须立即触发"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return kotlin.Unit.INSTANCE;
            });
            check(!result.isOk(), "取消必须返回失败状态");
            check(result.isCancelled(), "取消要单独归类，不能和普通失败混在一起");
        });
        worker.setUncaughtExceptionHandler((t, e) -> { throwables.add(e); });
        worker.start();
        check(entered.await(2, TimeUnit.SECONDS), "动作启动");
        check(!Actions.INSTANCE.confirmTouchReset(), "动作收尾前不得解除触摸安全阻止");
        RunResult busy = Actions.INSTANCE.run("second", token -> {
            throw new AssertionError("并行动作不得插入");
        });
        check(!busy.isOk(), "互斥拒绝并行动作");
        Actions.INSTANCE.cancel();
        worker.join(2000);
        check(cancelled.get() && !worker.isAlive() && !Actions.INSTANCE.getBusy(), "取消后释放动作锁");
        check(throwables.isEmpty(), "后台动作断言通过");
        check(Actions.INSTANCE.run("restart", token -> kotlin.Unit.INSTANCE).isOk(), "取消后可以重新启动");
        Actions.Token early = new Actions.Token();
        early.cancel();
        AtomicBoolean observed = new AtomicBoolean();
        early.attach(() -> { observed.set(true); return kotlin.Unit.INSTANCE; });
        check(observed.get(), "启动前取消不能丢失");
    }
    static final List<Throwable> throwables = new java.util.concurrent.CopyOnWriteArrayList<>();
    static void touchProcessSafety() throws Exception {
        String[] scripts = {
            "read go; printf 'TOUCH_IDLE\nTOUCH_OK\n'",
            "read go; printf 'TOUCH_RELEASE_FAILED UP failed\n'; exit 1",
            "read go; exit 9",
            "read go; cat >/dev/null; printf 'TOUCH_RELEASE_FAILED cancelled UP\n'; exit 2",
            "read go; exec sleep 5"
        };
        Actions actions = Actions.INSTANCE;
        for (int i = 0; i < scripts.length; i++) {
            actions.getGate().clear();
            final int scenario = i;
            Process child = new ProcessBuilder("/bin/sh", "-c", scripts[i]).redirectErrorStream(true).start();
            RunResult result = actions.run("process", token -> {
                try {
                    TouchProcess.INSTANCE.perform(child, 100, token, () -> {
                        if (scenario >= 3) {
                            token.cancel();
                            token.check();
                        }
                        return kotlin.Unit.INSTANCE;
                    }, line -> kotlin.Unit.INSTANCE, 100);
                    return kotlin.Unit.INSTANCE;
                } catch (Throwable e) { return raise(e); }
            });
            if (i == 0) check(result.isOk(), "正常退出必须同时有成功与空闲回执");
            else check(result.isDangerous() && actions.getGate().getLocked(),
                "异常退出、取消松手失败和强杀均必须锁住注入，scenario=" + i);
            child.waitFor(1, TimeUnit.SECONDS);
            check(!child.isAlive(), "测试子进程已回收");
        }
        actions.getGate().clear();
        RunResult cancelled = actions.run("cancel+release", token -> {
            InterruptedException e = new InterruptedException("cancel");
            e.addSuppressed(new GestureSequence.ReleaseFailedException("UP failed", null));
            return raise(e);
        });
        check(cancelled.isDangerous() && actions.getGate().getLocked(), "动作层也必须保留取消时的危险状态");
        actions.getGate().clear();
    }
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
        // ★ 这几个原来定义了却没被调用 —— "写了测试却没运行"正是要防的事，
        //   所以入口里显式列出来，新增测试也必须在这里挂号。
        releaseFailureIsFatal();
        failureClassification(); jumpMoveAndReleaseFailure(); injectGateLatches(); releaseFailureBlocksWalkAndKeepsStopUsable();
        walkBudget(); walkCoordinatesValidated();
        signerTrust(); settingsConflictPolicy(); safeScreenBounds(); screenMatch(); actionPacing(); pacingConfigurable();
        scheduling(); actions(); shell(); touchProcessSafety(); UpdateDownloadRegression.run();
        AccessibilityRegression.run();
        System.out.println(
            "PASS: 1:2:1 时序、1秒后单次跳跃、可选跳跃只走三段、按压设置、8+6 个取消阶段、" +
                "失败松手、闸门两档与失败禁止注入、1280x720安全悬浮区、屏幕记录三选一、注入重试、未按下不松手、" +
                "跳跃失败不致命、失败分类与触摸状态未知拉闸、走位预算按 4D 计、坐标校验（含跳跃与归一化）、" +
                "APK signer 信任锚、动作间隔两类且可调、独立排期、互斥重启、命令退出码/超时/中断/转义、" +
                "子进程收尾回执与强杀拉闸、下载大小/总时限/HTTPS跳转/半包清理"
        );
    }
}
