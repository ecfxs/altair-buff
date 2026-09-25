package com.altair.probe;

/**
 * 动作之间的最小间隔（纯逻辑，便于离线回归）。
 *
 * ## 为什么需要它
 * 这个项目里最难查的一类失败，是**注入成功但游戏没接受**：
 * 游戏要放完上一个技能/动作的动画才会接受下一次输入，而注入层只知道"事件送到了"。
 * 于是日志上一切正常，游戏里什么都没发生。
 * 时间上的间隔是唯一能防住它的东西，所以它必须是一条**成文的规则**，
 * 而不是散在循环里的几个 `Thread.sleep(1500)`。
 *
 * ## 两类间隔，不一样长
 * ```
 *   同类：补 BUFF → 补 BUFF      sameKindGapMs   —— 等上一个技能的施法动画
 *   换类：补 BUFF → 走位          switchKindGapMs —— 摇杆的 DOWN 不能撞在施法动画上
 *         走位   → 补 BUFF                         走位收尾是跳一下，同样要隔开
 * ```
 * 换类要留得更足：走位第一件事就是按住摇杆，被吞掉时**人一步不走却报「走位完成」**，
 * 比技能没上还难发现（技能至少能从 BUFF 图标看出来）。
 *
 * ## 为什么用 int 常量而不是 enum
 * 与 [GestureSequence] 同理：这是拿给 Kotlin 调用的 Java 类，嵌套 enum 会让 Kotlin
 * 编译器在"从 class 文件读"和"从 Java 源码读"之间解析不一致。int 常量没有这个问题。
 */
public final class ActionPacer {

    /** 补 BUFF。 */
    public static final int BUFF = 0;
    /** 原地走位（含收尾的跳跃）。 */
    public static final int WALK = 1;

    private final long sameKindGapMs;
    private final long switchKindGapMs;

    private long lastEndAt = Long.MIN_VALUE;
    private int lastKind = -1;

    public ActionPacer(long sameKindGapMs, long switchKindGapMs) {
        if (sameKindGapMs < 0 || switchKindGapMs < 0) {
            throw new IllegalArgumentException("间隔不能为负");
        }
        this.sameKindGapMs = sameKindGapMs;
        this.switchKindGapMs = switchKindGapMs;
    }

    /**
     * 现在开始 [next] 这个动作，还需要等多少毫秒（0 = 立刻可以开始）。
     *
     * 只看"上一次动作**结束**的时刻"，因为动画是从动作结束之后才开始放的。
     */
    public long delayBefore(int next, long now) {
        if (lastKind < 0) return 0;
        long need = (lastKind == next) ? sameKindGapMs : switchKindGapMs;
        long elapsed = now - lastEndAt;
        return elapsed >= need ? 0 : need - elapsed;
    }

    /** 记下一次动作已经结束（注入返回之后调用）。 */
    public void done(int kind, long now) {
        lastKind = kind;
        lastEndAt = now;
    }

    /** 当前记录的间隔，供日志/测试读取。 */
    public long sameKindGapMs() { return sameKindGapMs; }

    public long switchKindGapMs() { return switchKindGapMs; }
}
