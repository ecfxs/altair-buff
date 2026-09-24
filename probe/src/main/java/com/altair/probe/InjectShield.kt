package com.altair.probe

/**
 * 注入输入的「悬浮窗让路」闸门
 * ============================
 *
 * ## 它解决的问题
 * 底层用 `input` 注入触摸时，事件由系统按 **z 序**交给最上面那个「可触摸」窗口。
 * 悬浮面板/悬浮球就浮在游戏之上 —— 所以只要面板盖住了轮盘中心、技能图标或跳跃键的位置，
 * 注入的 DOWN/MOVE/UP 就**全部打在面板自己的按钮上**，游戏一个事件都收不到。
 *
 * 实机表现（用户报告）：展开「工具」区后点「试走位一次」—— 展开工具会让面板变高，
 * 从而盖住左下角轮盘；紧接着 `joystickWalk` 注入的第一个 DOWN 正好落在面板下部的
 * 按钮上（「复位窗口位置」会把面板收起成球、「连续标技能」会弹出采点层），
 * 用户看到的就是**「一点试走位，悬浮窗自己消失了」**，而角色根本没动。
 *
 * 同一类事故还有：任务运行中，用户正好在标注（采点层是全屏可触摸的），引擎注入的技能
 * 点击被采点层吃掉 —— 结果是**静默地存下一个错误坐标**。这正是本项目最忌讳的失败模式。
 *
 * ## 做法
 * 注入前后把覆盖层切成 `FLAG_NOT_TOUCHABLE`，事件就正常落到游戏上。用**计数**而不是布尔，
 * 是因为悬浮窗的「试走位」与引擎的动作是两条线程，可能同时在注入：只有最外层那一次
 * 结束时才恢复可触摸。
 *
 * ## 谁注册
 * [OverlayService] 在 `onCreate`/`onDestroy` 里注册/注销。没有悬浮窗时（主界面里直接用
 * [Probe]）注册者为空，[withPassThrough] 退化成"直接执行"，调用方无需关心。
 */
object InjectShield {

    /** 让覆盖层让路的实现：true = 注入开始（覆盖层停止吃触摸），false = 注入结束。 */
    @Volatile
    private var handler: ((Boolean) -> Unit)? = null

    private val lock = Any()

    /** 嵌套深度。两条线程同时注入时，只有最外层会真正去改窗口参数。 */
    private var depth = 0

    fun bind(h: ((Boolean) -> Unit)?) {
        handler = h
    }

    /**
     * 执行 [block]（一次注入），期间覆盖层不吃触摸。
     *
     * 注意 handler 必须是**同步**生效的：闸门没关好就把注入发出去，等于没做。
     */
    fun <T> withPassThrough(block: () -> T): T {
        val h = handler ?: return block()
        val outermost = synchronized(lock) { depth++ == 0 }
        if (outermost) runCatching { h(true) }
        try {
            return block()
        } finally {
            val last = synchronized(lock) { --depth == 0 }
            if (last) runCatching { h(false) }
        }
    }
}
