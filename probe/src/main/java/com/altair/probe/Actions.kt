package com.altair.probe

import java.util.concurrent.locks.ReentrantLock

/**
 * 所有自动、手动触摸共用一把动作锁；停止无需等待动作锁。
 *
 * ## 返回值为什么不是 `Pair<Boolean, String>`
 * 那个类型只有两格，装不下"未确认松手"——它和"注入被拒"长得一模一样，都是
 * `false + 一句文案`，于是**危险结局被当成普通失败丢掉**，引擎照常继续下一轮注入。
 * 现在返回 [RunResult]，失败必然带着 [ActionFailure.Fault]，调用方想忽略都难。
 *
 * ## 闸门（[InjectGate]）为什么在锁**之外**
 * 闸门关闭时注入被阻止，此时连动作锁都不该碰：锁代表"有个动作正在跑"，而这里根本
 * 没跑起来。放在锁外还有个好处——[Actions.busy] 保持 false，界面能正常显示
 * "怎么解除阻止"，而不是看起来卡在运行中。
 */
object Actions {

    class Token {
        @Volatile var cancelled = false
            private set
        private var onCancel: (() -> Unit)? = null
        @Synchronized fun cancel() {
            cancelled = true
            onCancel?.invoke()
        }
        @Synchronized fun attach(callback: (() -> Unit)?) {
            onCancel = callback
            if (cancelled) callback?.invoke()
        }
        fun check() {
            if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedException("动作已取消")
        }
    }

    /**
     * 会话级注入闸门。未确认松手后由它挡住后续注入，只有用户确认才解锁。
     */
    val gate = InjectGate()

    /** 所有注入动作共用一把锁：两个动作同时发触摸，等于两只手抢一个摇杆。 */
    private val lock = ReentrantLock()

    @Volatile private var token: Token? = null
    @Volatile var label = ""
        private set
    val busy: Boolean get() = lock.isLocked
    fun cancel() { token?.cancel() }

    /** 设置切换也取得动作锁，避免检查空闲与写入模式之间启动另一个手动动作。 */
    fun whenIdle(change: () -> Unit): Boolean {
        if (!lock.tryLock()) return false
        return try { change(); true } finally { lock.unlock() }
    }

    /** 用户确认只允许发生在上一动作完成收尾后，避免边清锁边丢弃迟到的松手失败。 */
    fun confirmTouchReset(): Boolean {
        if (!lock.tryLock()) return false
        return try { gate.clear(); true } finally { lock.unlock() }
    }

    /**
     * 跑一个会注入触摸的动作。
     *
     * 结局处理：
     *   - 成功 → [RunResult.isOk]，文案只说"输入已完成"，**不**声称游戏里已经生效；
     *   - 取消 → 照旧返回，不拉闸（用户自己叫停的，触摸状态由他自己的操作决定）；
     *   - 普通失败 → 返回失败类别，交给引擎计数/熔断；
     *   - 未确认松手 → 先拉闸再返回，之后任何注入动作都会在入口被 [RunResult.isBlocked] 挡住。
     *
     * [finally] 里的清理顺序：闸门在 catch 里拉上，token 在 finally 里释放 ——
     * 即使 worker 被中断，也不会留下一个解不开的锁。
     *
     * ★ 参数类型是 Kotlin 的 `(Token) -> Unit` 而不是自定义的 SAM 接口：手写的 Java 回归测试
     * 也直接调用这个方法，用 Kotlin 函数类型时两端写法一致（Java 侧 `token -> { ...; return Unit.INSTANCE; }`），
     * 不会出现"Kotlin 里看着能过、Java 测试里编译不过"的偏差。
     */
    fun run(name: String, block: (Token) -> Unit): RunResult {
        gate.blockReason()?.let { reason ->
            LogBus.emit("🛑 $name 未执行：$reason")
            return RunResult.blocked(reason)
        }
        if (!lock.tryLock()) {
            return RunResult.notRun(0L, ActionFailure.Fault.FAILED, "已有动作执行中，请稍后重试")
        }
        // 等待锁之前的检查不能替代此处检查：上一动作可能刚刚锁上安全闸门。
        gate.blockReason()?.let { reason ->
            lock.unlock()
            return RunResult.blocked(reason)
        }
        val current = Token()
        token = current
        label = name
        val startedAt = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000
        try {
            block(current)
            current.check()
            return RunResult.success(elapsedMs(), "输入已完成")
        } catch (e: Exception) {
            val f = ActionFailure.from(e, "$name 执行失败")
            if (f.isReleaseUnknown()) {
                val first = gate.lock(f.message)
                if (first) {
                    LogBus.emit(
                        "🛑 未确认松手：已阻止后续自动注入。" +
                            "请在游戏里确认触摸已复位，再解除阻止（主界面准备情况 / 悬浮面板状态行）"
                    )
                }
            }
            return RunResult.failure(elapsedMs(), f)
        } finally {
            current.attach(null)
            token = null
            label = ""
            lock.unlock()
        }
    }
}
