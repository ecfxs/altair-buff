package com.altair.probe

/**
 * 注入期间让覆盖层让路
 * ====================
 *
 * `input` 注入的触摸由系统按 **z 序**交给最上面那个**可触摸**窗口。悬浮面板/球就浮在游戏
 * 之上，所以只要它盖住了轮盘或技能键的位置，注入的 DOWN/MOVE/UP 就全打在**面板自己的按钮**
 * 上（实机表现为「一点试走位，悬浮窗自己消失了」，而角色一步没动）。
 *
 * ## 两档闸门，代价完全不同
 *
 * | 档 | 做什么 | 用在哪 |
 * |---|---|---|
 * | [Mode.INJECT] | 只切 `FLAG_NOT_TOUCHABLE` | 单次点击（补技能）。几十毫秒，窗口不动，用户看不见 |
 * | [Mode.WALK] | 切 flag **并且**把与注入区重叠的窗口缩成 1×1 挪到 (0,0) | 整段走位。轮盘在左下角，与展开面板重叠是常态 |
 *
 * ★ 这两档**必须分开**。曾经把 WALK 的做法无条件套在两档上（bind 时丢掉了 mode 参数），
 * 结果是每补一个技能，面板就被挪走再放回一次 —— 一轮 4 个技能就是 4 次可见的跳动。
 *
 * 为什么 WALK 不能只靠 flag：挪窗口是**几何事实**，不依赖 ROM 是否正确实现那个 flag。
 *
 * 闸门由 [Probe]/[WalkFlow] 用 [aroundInject] / [aroundWalk] 包住。**让路失败时禁止注入**
 * （异常直接从 `h(mode, true, …)` 冒出去，`block` 不会执行）；退出路径始终恢复覆盖层。
 */
object InjectShield {

    enum class Mode { INJECT, WALK }

    @Volatile private var handler: ((Mode, Boolean, Int, Int, Int, Int) -> Unit)? = null

    /**
     * 让路能力尚未绑定时默认拒绝输入。只有明确声明当前没有需要让路的覆盖层时，
     * 才可绑定 no-op adapter（例如离线测试或 Activity 尚未启动覆盖层的诊断）。
     */
    @Volatile private var allowUnbound = false
    private val handlerMonitor = Object()
    private const val READY_TIMEOUT_MS = 8_000L

    fun bind(h: ((Mode, Boolean, Int, Int, Int, Int) -> Unit)?) = bind(h, false)

    fun bind(h: ((Mode, Boolean, Int, Int, Int, Int) -> Unit)?, allowWithoutHandler: Boolean) {
        synchronized(handlerMonitor) {
            handler = h
            allowUnbound = allowWithoutHandler
            handlerMonitor.notifyAll()
        }
    }

    private fun currentHandler(): ((Mode, Boolean, Int, Int, Int, Int) -> Unit)? {
        if (allowUnbound) return handler
        val deadline = System.nanoTime() + READY_TIMEOUT_MS * 1_000_000
        synchronized(handlerMonitor) {
            while (handler == null && !allowUnbound) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "悬浮窗让路尚未就绪，已阻止触摸注入" }
                val waitMs = (remaining / 1_000_000).coerceAtLeast(1)
                handlerMonitor.wait(waitMs)
            }
            return handler
        }
    }

    /** 单次注入：只置灰，不动几何位置。 */
    fun <T> aroundInject(l: Int, t: Int, r: Int, b: Int, block: () -> T): T =
        gate(Mode.INJECT, l, t, r, b, block)

    /** 整段走位：置灰 + 把重叠窗口挪走 + 挂一个快捷停止按钮。 */
    fun <T> aroundWalk(l: Int, t: Int, r: Int, b: Int, block: () -> T): T =
        gate(Mode.WALK, l, t, r, b, block)

    private fun <T> gate(mode: Mode, l: Int, t: Int, r: Int, b: Int, block: () -> T): T {
        val h = currentHandler() ?: run {
            check(allowUnbound) { "悬浮窗让路尚未就绪，已阻止触摸注入" }
            return block()
        }
        h(mode, true, l, t, r, b)                 // 让路失败 → 直接抛出，绝不注入
        val outcome = runCatching(block)
        // 恢复失败不能假报成功；若动作本身也失败，则保留动作原错并附加恢复错误。
        val restore = runCatching { h(mode, false, l, t, r, b) }
        val restoreError = restore.exceptionOrNull()
        if (restoreError != null) {
            LogBus.emit("⚠ 让路恢复失败：${restoreError.message}")
            val actionError = outcome.exceptionOrNull()
            if (actionError != null) actionError.addSuppressed(restoreError)
            else throw IllegalStateException("悬浮窗恢复失败，动作结果不可信", restoreError)
        }
        return outcome.getOrThrow()
    }
}
