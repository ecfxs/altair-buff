package com.altair.probe;

import java.util.Locale;

/**
 * 一次动作的结局：成功、耗时、以及失败时的**类别**。
 *
 * 取代原来的 `Pair<Boolean, String>`。那个类型只有两个格子，装不下"未确认松手"这种
 * 必须改变后续行为的结局 —— 它和"注入被拒"看起来一模一样，都是 `false + 一句文案`。
 * 判定逻辑集中在 [ActionFailure]，这里只负责把它和耗时一起带走。
 *
 * ## 为什么用 Java 写
 * 这是**手写的 Java 回归测试**（`AutomationRegression`）和 Kotlin 生产代码共用的类型。
 * Kotlin 的 `is` 前缀属性在 Java 侧的方法名容易记错（`isOk` 的属性在 Java 里到底是
 * `isOk()` 还是 `getOk()` 一眼看不出来），而这个类的 getter 名字写错一次就是一次编译失败。
 * 写成 Java，getter 是什么名字一眼可查，Kotlin 侧照旧用属性语法访问。
 */
public final class RunResult {

    private final boolean ok;
    private final long millis;
    private final ActionFailure.Fault fault;
    private final String message;
    private final boolean blocked;

    private RunResult(boolean ok, long millis, ActionFailure.Fault fault, String message, boolean blocked) {
        this.ok = ok;
        this.millis = millis;
        this.fault = fault;
        this.message = message;
        this.blocked = blocked;
    }

    public static RunResult success(long millis, String message) {
        return new RunResult(true, millis, null, message, false);
    }

    /** 没跑起来就被挡住（闸门关闭/互斥拒绝），耗时按 0 记。 */
    public static RunResult notRun(long millis, ActionFailure.Fault fault, String message) {
        return new RunResult(false, millis, fault, message, false);
    }

    public static RunResult failure(long millis, ActionFailure failure) {
        return new RunResult(false, millis, failure.fault, failure.message, false);
    }

    /** 闸门关闭：动作**没有执行**，返回 [InjectGate.blockReason] 的原因。 */
    public static RunResult blocked(String reason) {
        return new RunResult(false, 0L, ActionFailure.Fault.RELEASE_UNKNOWN, reason, true);
    }

    public boolean isOk() {
        return ok;
    }

    public long getMillis() {
        return millis;
    }

    public ActionFailure.Fault getFault() {
        return fault;
    }

    public String getMessage() {
        return message;
    }

    /** 是否因为闸门关闭而**根本没能开始**（区别于"跑了但失败"）。 */
    public boolean isBlocked() {
        return blocked;
    }

    public boolean isReleaseUnknown() {
        return fault == ActionFailure.Fault.RELEASE_UNKNOWN;
    }

    public boolean isCancelled() {
        return fault == ActionFailure.Fault.CANCELLED;
    }

    /**
     * 危险结局：触摸状态未知，不能继续自动注入。
     *
     * 被闸门挡住（[isBlocked]）不算危险结局：那一刻**本次动作根本没执行**，
     * 触摸状态没有变化，日志不该把它说成"未确认松手"。
     */
    public boolean isDangerous() {
        return !blocked && isReleaseUnknown();
    }

    /** 日志/界面文案：成功只说"输入已完成"，**不**声称游戏里已经生效。 */
    public String describe(String label) {
        String head = label == null || label.isEmpty() ? "动作" : label;
        if (ok) return head + "：输入已完成（" + millis + "ms）";
        if (blocked) return head + " 未执行：" + message;
        if (isReleaseUnknown()) {
            return "⚠ " + head + "：未确认松手（系统未回执 UP），触摸状态未知 —— " + message;
        }
        if (isCancelled()) return head + "：已取消";
        return head + " 失败：" + message;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "RunResult{ok=%s, %dms, fault=%s, blocked=%s, %s}",
                ok, millis, fault, blocked, message);
    }
}
