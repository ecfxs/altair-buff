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
 * 实机表现（用户报告）：展开「工具」区后点「试走位一次」—— 展开工具会让面板变高
 * （720p 横屏下面板最高能到屏幕 88%），从而盖住左下角轮盘；紧接着 `joystickWalk`
 * 注入的第一个 DOWN 正好落在面板下部的按钮上（「复位窗口位置」会把面板收起成球、
 * 「连续标技能」会弹出采点层、标注按钮会弹采点层），用户看到的就是
 * **「一点试走位，悬浮窗自己消失了」**，而角色根本没动 —— 两个症状同一个根因。
 *
 * 同一类事故还有：任务运行中，用户正好在标注（采点层是全屏可触摸的），引擎注入的技能
 * 点击被采点层吃掉 —— 结果是**静默地存下一个错误坐标**。这正是本项目最忌讳的失败模式。
 *
 * ## 两种让路方式
 * ```
 *   INJECT  单次注入（点击技能 / 走位的每一段）：把覆盖层临时置灰（FLAG_NOT_TOUCHABLE）。
 *           轻量，覆盖所有注入路径。
 *   WALK    整段走位（4 段走位 + 跳）：把**与注入区域重叠**的窗口直接挪到屏幕外，
 *           走完再挪回来。比置灰更硬 —— 不依赖 ROM 是否正确实现 NOT_TOUCHABLE，
 *           因为窗口根本不在那个坐标上了。
 * ```
 * 走位用 WALK 而不是只靠 INJECT，是因为轮盘在左下角、而展开后的面板高度能盖住半个屏幕：
 * 面板与摇杆区重叠是**常态**，这里必须保证"一定碰不到"，不能只指望一个 flag。
 *
 * ## 谁注册
 * [OverlayService] 在 `onCreate`/`onDestroy` 里注册/注销。没有悬浮窗时（主界面里直接用
 * [Probe]）注册者为空，调用方逻辑退化成"直接执行"，行为完全不变。
 */
object InjectShield {

    /** 让路方式。 */
    enum class Mode { INJECT, WALK }

    /**
     * 让路开关的实现。
     *
     * [Mode] + 是否开始 + 注入区域的屏幕像素矩形 `(left, top, right, bottom)`。
     * 必须是**同步**生效的：闸门没关好就把注入发出去，等于没做。
     */
    @Volatile
    private var handler: ((Mode, Boolean, Int, Int, Int, Int) -> Unit)? = null

    private val lock = Any()

    /** 嵌套深度。悬浮窗的「试走位」与引擎的动作是两条线程，可能同时注入。 */
    private var depth = 0

    /** 最外层那一次的门（方式 + 区域），嵌套的内层不重复开合。 */
    private var outerMode: Mode? = null
    private var outerRect: IntArray? = null

    fun bind(h: ((Mode, Boolean, Int, Int, Int, Int) -> Unit)?) {
        handler = h
    }

    /** 单次注入的让路（矩形就是这一次触摸的范围；点是零面积矩形）。 */
    fun <T> aroundInject(l: Int, t: Int, r: Int, b: Int, block: () -> T): T =
        gate(Mode.INJECT, l, t, r, b, block)

    /** 整段走位的让路。**必须**包住完整的 4 段走位 + 跳，否则每段都要重新挪一次窗口。 */
    fun <T> aroundWalk(l: Int, t: Int, r: Int, b: Int, block: () -> T): T =
        gate(Mode.WALK, l, t, r, b, block)

    private fun <T> gate(mode: Mode, l: Int, t: Int, r: Int, b: Int, block: () -> T): T {
        val h = handler ?: return block()
        val outermost = synchronized(lock) {
            if (depth++ == 0) {
                outerMode = mode
                outerRect = intArrayOf(l, t, r, b)
                true
            } else {
                false
            }
        }
        if (outermost) runCatching { h(mode, true, l, t, r, b) }
        try {
            return block()
        } finally {
            val last = synchronized(lock) { --depth == 0 }
            if (last) {
                val m = outerMode ?: mode
                val rect = outerRect ?: intArrayOf(l, t, r, b)
                synchronized(lock) {
                    outerMode = null
                    outerRect = null
                }
                runCatching { h(m, false, rect[0], rect[1], rect[2], rect[3]) }
            }
        }
    }
}
