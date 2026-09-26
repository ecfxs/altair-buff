package com.altair.probe;

/**
 * 动作失败的**类别**，以及从异常链里把它认出来的唯一入口。
 *
 * ## 为什么不能只有一句 message
 * 原来动作层返回 `Pair<Boolean, String>`：成功/失败 + 人话文案。于是四种本质完全不同的
 * 结局被压缩成同一种"失败"：
 *
 * | 结局 | 真实含义 | 能不能继续自动注入 |
 * |---|---|---|
 * | 输入已完成 | 事件确实送进去了 | 可以 |
 * | 动作已取消 | 用户按了停止 | 可以（用户自己叫停的） |
 * | 普通失败 | 注入被拒、超时、不在前台…… | 可以，但要计数熔断 |
 * | **未确认松手** | DOWN 可能还按在屏幕上 | **绝对不行** |
 *
 * 前三种都是"这次没成"，第四种是"设备现在处于未知触摸状态"——继续注入等于在一个还按着的
 * 手指上叠新事件，轻则动作全乱，重则把摇杆卡死在某个方向。所以它必须有独立的类型，
 * 一路传到引擎，而不是变成一句文案。
 *
 * ## 为什么抑制异常也算
 * [GestureSequence] 在"动作本身已经失败、而 UP 也没被确认"时，会把释放失败挂成
 * `suppressed`（不能让它顶掉真正的主错误）。只看 `e instanceof ReleaseFailedException`
 * 会漏掉这种情况 —— 那正是实测里"未确认松手，但走位正常返回"的成因。所以这里把
 * **cause 链和 suppressed 链一起看**。
 */
public final class ActionFailure {

    /** 失败类别。顺序即"严重程度"，[ordinal] 越大越不能忽略。 */
    public enum Fault {
        /** 用户停止/取消。 */
        CANCELLED,
        /** 普通失败：注入被拒、超时、不在前台、几何失效…… */
        FAILED,
        /** 触摸状态未知：已经按下但系统未确认松手。 */
        RELEASE_UNKNOWN,
    }

    public final Fault fault;
    public final String message;

    private ActionFailure(Fault fault, String message) {
        this.fault = fault;
        this.message = message;
    }

    public static ActionFailure cancelled(String message) {
        return new ActionFailure(Fault.CANCELLED, message);
    }

    public static ActionFailure failed(Throwable e) {
        return new ActionFailure(Fault.FAILED, text(e));
    }

    public static ActionFailure releaseUnknown(Throwable e) {
        return new ActionFailure(Fault.RELEASE_UNKNOWN, text(e));
    }

    public boolean isReleaseUnknown() {
        return fault == Fault.RELEASE_UNKNOWN;
    }

    public boolean isCancelled() {
        return fault == Fault.CANCELLED;
    }

    /**
     * 归类一个异常。只看异常链本身（含 suppressed），不做任何副作用。
     *
     * @param defaultMessage 异常没有 message 时用的兜底人话；可为 null。
     */
    public static ActionFailure from(Throwable e, String defaultMessage) {
        if (carriesReleaseFailure(e)) return releaseUnknown(e);
        if (e instanceof InterruptedException) return cancelled(messageOf(e, defaultMessage));
        return new ActionFailure(Fault.FAILED, messageOf(e, defaultMessage));
    }

    /** 已知类别时直接构造（取消、未确认松手这类由调用方自己判定的情形）。 */
    public static ActionFailure from(Fault fault, Throwable e, String defaultMessage) {
        return new ActionFailure(fault, messageOf(e, defaultMessage));
    }

    /**
     * 异常链里是否藏着"未确认松手"。
     *
     * 递归看 `cause`，也看每层的 `suppressed` —— 抑制异常正是"主错误已经抛出去了，
     * 释放失败只能搭车"的那条路径。
     */
    public static boolean carriesReleaseFailure(Throwable e) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.ArrayDeque<Throwable> pending = new java.util.ArrayDeque<>();
        if (e != null) pending.add(e);
        while (!pending.isEmpty()) {
            Throwable t = pending.removeFirst();
            if (!seen.add(t)) continue;
            if (t instanceof GestureSequence.ReleaseFailedException) return true;
            java.util.Collections.addAll(pending, t.getSuppressed());
            if (t.getCause() != null) pending.add(t.getCause());
        }
        return false;
    }

    /** 取异常链里第一个有 message 的，够用且不会因为包装层没有 message 就丢信息。 */
    public static String text(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 16; depth++) {
            String m = t.getMessage();
            if (m != null && !m.trim().isEmpty()) return m;
            t = t.getCause() == t ? null : t.getCause();
        }
        return e.getClass().getSimpleName();
    }

    private static String messageOf(Throwable e, String fallback) {
        String m = text(e);
        if (m != null && !m.trim().isEmpty()) return m;
        return fallback == null ? e.getClass().getSimpleName() : fallback;
    }
}
