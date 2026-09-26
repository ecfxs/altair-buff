package com.altair.probe;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 直接运行生产续接/收尾逻辑，不需要 Android 或真实触摸。 */
public final class AccessibilityRegression {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static AccessibleGestureRunner.Check checkStop(AtomicBoolean stop) {
        return () -> { if (stop.get()) throw new InterruptedException("停止"); };
    }

    static void holdAndRelease() throws Exception {
        List<AccessibleGestureRunner.Segment> sent = new ArrayList<>();
        AccessibleGestureRunner runner = new AccessibleGestureRunner((segment, receipt) -> {
            sent.add(segment); check(receipt.start(), "首次提交可接受"); receipt.complete();
        }, () -> {});
        runner.hold(100, 200, 40, 200, 1200);
        check(sent.get(0).fromX == 100 && sent.get(0).x == 40, "从中心推到左端点");
        check(!sent.get(0).continuation, "初始片段按下");
        long total = 0;
        for (int i = 0; i < sent.size(); i++) {
            AccessibleGestureRunner.Segment s = sent.get(i);
            total += s.millis;
            check(s.millis <= 150, "单段时长受限");
            check(s.continuation == (i > 0), "后续片段保持同一手指");
            check(s.keepsDown == (i < sent.size() - 1), "只有最终片段释放");
        }
        check(total == 1200, "分段不改变计划按压时间");
        sent.clear(); runner.hold(90, 80, 90, 80, 90);
        check(sent.size() == 1 && !sent.get(0).keepsDown, "短点击不续接且释放");
    }

    static void stopAfterContinuation() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        List<AccessibleGestureRunner.Segment> sent = new ArrayList<>();
        AccessibleGestureRunner runner = new AccessibleGestureRunner((s, r) -> {
            sent.add(s); r.start(); r.complete(); if (!s.cleanup) stop.set(true);
        }, checkStop(stop));
        try { runner.hold(100, 200, 40, 200, 600); throw new AssertionError("必须取消"); }
        catch (InterruptedException expected) { }
        check(sent.size() == 2, "停止后只有当前段和一个释放段");
        AccessibleGestureRunner.Segment release = sent.get(1);
        check(release.cleanup && release.continuation && !release.keepsDown && release.x == 40,
                "在已知终点释放，不注入新点击");
    }

    static void walking() throws Exception {
        List<Integer> starts = new ArrayList<>();
        List<Long> lengths = new ArrayList<>();
        List<Long> pauses = new ArrayList<>();
        long[] duration = {0};
        AccessibleGestureRunner runner = new AccessibleGestureRunner((s, r) -> {
            if (!s.continuation) { starts.add(s.x); duration[0] = 0; }
            duration[0] += s.millis;
            if (!s.keepsDown) lengths.add(duration[0]);
            r.start(); r.complete();
        }, () -> {});
        WalkPlan.Plan plan = WalkPlan.walkWithJump(100, 200, 40, 160, 900, 500, 600, 90, 1280, 720);
        runner.perform(plan.action, pauses::add);
        check(starts.equals(Arrays.asList(40, 160, 40, 900)), "与 Root 使用同一走位方向及跳跃坐标");
        check(lengths.equals(Arrays.asList(600L, 1200L, 600L, 90L)), "无障碍完整 1:2:1 与跳跃按压");
        check(pauses.equals(Arrays.asList(250L, 250L, 1000L)), "两次段间等待及跳前等待");
        check(lengths.stream().mapToLong(Long::longValue).sum() + pauses.stream().mapToLong(Long::longValue).sum()
                == plan.budgetMs, "完整手势计划与生产预算一致");
        starts.clear(); lengths.clear(); pauses.clear();
        runner.perform(WalkPlan.walkOnly(100, 200, 40, 160, 600, 1280, 720).action, pauses::add);
        check(starts.size() == 3 && pauses.equals(Arrays.asList(250L, 250L)), "无跳跃分支不注入第四个触摸");
        AtomicBoolean stop = new AtomicBoolean(); starts.clear();
        runner = new AccessibleGestureRunner((s, r) -> { if (!s.continuation) starts.add(s.x); r.start(); r.complete(); }, checkStop(stop));
        try { runner.perform(plan.action, ms -> stop.set(true)); throw new AssertionError("等待期间停止必须取消"); }
        catch (InterruptedException expected) { }
        check(starts.equals(Arrays.asList(40)), "取消后不开始右段或跳跃");
    }

    static void cancellationWaitsForReceipt() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<AccessibleGestureRunner.Receipt> active = new AtomicReference<>();
        CountDownLatch submitted = new CountDownLatch(1);
        List<AccessibleGestureRunner.Segment> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean restoredInterrupt = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                new AccessibleGestureRunner((s, r) -> {
                    sent.add(s); r.start();
                    if (s.cleanup) r.complete(); else { active.set(r); submitted.countDown(); }
                }, checkStop(stop)).hold(100, 200, 40, 200, 600);
            } catch (Throwable e) { error.set(e); }
            restoredInterrupt.set(Thread.currentThread().isInterrupted());
        });
        worker.start(); check(submitted.await(1, TimeUnit.SECONDS), "手势已提交");
        stop.set(true); worker.interrupt();
        Thread.sleep(40);
        check(worker.isAlive() && sent.size() == 1, "停止不能在未知端点立刻追加释放或结束动作锁");
        active.get().complete(); worker.join(2000);
        check(!worker.isAlive() && error.get() instanceof InterruptedException && restoredInterrupt.get(),
                "收尾后报告取消并恢复中断标记");
        check(sent.size() == 2 && sent.get(1).cleanup, "收到当前段回执后才释放");
    }

    static void missingAndLateCallbacks() throws Exception {
        AtomicReference<AccessibleGestureRunner.Receipt> queued = new AtomicReference<>();
        try {
            new AccessibleGestureRunner((s, r) -> queued.set(r), () -> {}, 5).hold(1, 1, 1, 1, 1);
            throw new AssertionError("排队超时必须失败");
        } catch (IllegalStateException expected) { }
        check(!queued.get().start(), "超时的主线程任务不能迟到注入");
        AtomicReference<AccessibleGestureRunner.Receipt> submitted = new AtomicReference<>();
        try {
            new AccessibleGestureRunner((s, r) -> { r.start(); submitted.set(r); }, () -> {}, 5)
                    .hold(1, 1, 1, 1, 1);
            throw new AssertionError("缺回执不能成功");
        } catch (GestureSequence.ReleaseFailedException expected) { }
        submitted.get().complete();
        check(!submitted.get().start(), "迟到完成不会重新发送");
    }

    static void refusalAndUnknownRelease() throws Exception {
        List<AccessibleGestureRunner.Segment> sent = new ArrayList<>();
        try {
            new AccessibleGestureRunner((s, r) -> { sent.add(s); r.start(); r.reject(); }, () -> {})
                    .hold(1, 1, 1, 1, 90);
            throw new AssertionError("拒绝不能成功");
        } catch (IllegalStateException expected) { }
        check(sent.size() == 1, "初次明确拒绝后不发释放");

        AtomicBoolean stop = new AtomicBoolean(); sent.clear();
        try {
            new AccessibleGestureRunner((s, r) -> {
                sent.add(s); r.start();
                if (s.cleanup) r.reject(); else { r.complete(); stop.set(true); }
            }, checkStop(stop)).hold(10, 10, 20, 10, 600);
            throw new AssertionError("释放失败不能成功");
        } catch (Exception e) {
            check(ActionFailure.from(e, "失败").isReleaseUnknown(), "释放失败优先于取消");
        }
        Actions.INSTANCE.confirmTouchReset();
        RunResult result = Actions.INSTANCE.run("无障碍取消回执", token -> {
            try {
                new AccessibleGestureRunner((s, r) -> { r.start(); r.cancel(); }, () -> {})
                        .hold(1, 1, 1, 1, 90);
            } catch (Exception e) { return AutomationRegression.raise(e); }
            return kotlin.Unit.INSTANCE;
        });
        check(result.isDangerous() && Actions.INSTANCE.getGate().getLocked(), "未知释放锁住全局注入");
        AtomicBoolean called = new AtomicBoolean();
        RunResult blocked = Actions.INSTANCE.run("另一输入方式", token -> { called.set(true); return kotlin.Unit.INSTANCE; });
        check(blocked.isBlocked() && !called.get(), "不能通过换通道绕过安全锁");
        check(Actions.INSTANCE.confirmTouchReset(), "收尾后可人工解除");
    }

    static ForegroundWindows.Window window(int type, boolean active, boolean focus, String pkg) {
        return new ForegroundWindows.Window(type, 0, active, focus, false, pkg);
    }
    static void foreground() {
        ForegroundWindows.Window game = window(1, true, true, "game.pkg");
        check(ForegroundWindows.resolve(Arrays.asList(game, window(3, false, false, null)), true, false, "own.pkg")
                .equals("game.pkg"), "系统非焦点窗口不抢游戏前台");
        check(ForegroundWindows.resolve(Arrays.asList(window(1, false, true, "game.pkg"),
                window(3, true, false, "own.pkg")), true, false, "own.pkg").equals("game.pkg"),
                "自家非聚焦面板临时 active 时允许下层游戏");
        for (List<ForegroundWindows.Window> snapshot : Arrays.asList(
                Arrays.asList(game, window(1, false, false, "other.pkg")),
                Arrays.asList(game, window(3, true, false, "system.pkg")),
                Arrays.asList(game, window(2, false, false, "keyboard.pkg")),
                Arrays.asList(game, window(4, true, false, "other.pkg")),
                Arrays.asList(game, window(3, true, true, "own.pkg")),
                Arrays.asList(window(1, false, true, "game.pkg"), window(3, true, false, null)),
                Arrays.asList(window(1, true, true, null)),
                Arrays.asList(new ForegroundWindows.Window(1, 0, true, true, true, "game.pkg")),
                Arrays.asList(new ForegroundWindows.Window(1, 2, true, true, false, "game.pkg")))) {
            check(ForegroundWindows.resolve(snapshot, true, false, "own.pkg").isEmpty(), "不明确窗口必须拒绝");
        }
        check(ForegroundWindows.resolve(Arrays.asList(game), true, true, "own.pkg").isEmpty(), "锁屏拒绝");
        check(ForegroundWindows.resolve(Arrays.asList(game), false, false, "own.pkg").isEmpty(), "息屏拒绝");
    }

    static void fileCopy() throws Exception {
        File target = Files.createTempFile("apk-copy-test", ".apk").toFile();
        try {
            byte[] bytes = new byte[1500]; Arrays.fill(bytes, (byte) 7);
            ApkFiles.copy(new ByteArrayInputStream(bytes), target, 2000);
            check(Arrays.equals(bytes, Files.readAllBytes(target.toPath())), "保留完整文件字节");
            try { ApkFiles.copy(new ByteArrayInputStream(bytes), target, 1200); throw new AssertionError("超限应失败"); }
            catch (java.io.IOException expected) { }
            check(!target.exists(), "超限删除半包");
            try { ApkFiles.copy(new ByteArrayInputStream(new byte[0]), target); throw new AssertionError("空文件应失败"); }
            catch (java.io.IOException expected) { }
            check(!target.exists(), "空文件删除");
        } finally { target.delete(); }
    }

    static void modeChangeWaitsForAction() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicReference<RunResult> result = new AtomicReference<>();
        Thread action = new Thread(() -> result.set(Actions.INSTANCE.run("正在执行", token -> {
            entered.countDown();
            try { finish.await(); } catch (InterruptedException e) { return AutomationRegression.raise(e); }
            return kotlin.Unit.INSTANCE;
        })));
        action.start(); check(entered.await(1, TimeUnit.SECONDS), "已持有动作锁");
        AtomicBoolean changed = new AtomicBoolean();
        try {
            check(!Actions.INSTANCE.whenIdle(() -> { changed.set(true); return kotlin.Unit.INSTANCE; }) && !changed.get(),
                    "动作中不能切换输入模式");
        } finally { finish.countDown(); action.join(2000); }
        check(result.get().isOk(), "旧动作正常收尾");
        Actions.INSTANCE.getGate().lock("模拟未确认释放");
        check(Actions.INSTANCE.whenIdle(() -> { changed.set(true); return kotlin.Unit.INSTANCE; }), "空闲可显式选择方式");
        check(changed.get() && Actions.INSTANCE.getGate().getLocked(), "更换方式不清除安全闸门");
        Actions.INSTANCE.confirmTouchReset();
    }

    public static void run() throws Exception {
        holdAndRelease(); walking(); stopAfterContinuation(); cancellationWaitsForReceipt();
        missingAndLateCallbacks(); refusalAndUnknownRelease(); foreground(); fileCopy(); modeChangeWaitsForAction();
        System.out.println("PASS: 无障碍续接/释放、停止等待回执、中断恢复、迟到提交、取消/超时拉闸、前台窗口门禁、本地 APK 复制边界");
    }
}
