package com.altair.probe

import java.util.concurrent.locks.ReentrantLock

/** 所有自动、手动触摸共用一把动作锁；停止无需等待动作锁。 */
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
    private val lock = ReentrantLock()
    @Volatile private var token: Token? = null
    @Volatile var label = ""
        private set
    val busy: Boolean get() = lock.isLocked
    fun cancel() { token?.cancel() }

    fun run(name: String, block: (Token) -> Unit): Pair<Boolean, String> {
        if (!lock.tryLock()) return false to "已有动作执行中，请稍后重试"
        val current = Token()
        token = current
        label = name
        return try {
            block(current)
            current.check()
            true to "$name：输入已完成"
        } catch (e: InterruptedException) {
            false to "$name：已取消"
        } catch (e: Exception) {
            false to "$name 失败：${e.message ?: e.javaClass.simpleName}"
        } finally {
            current.attach(null)
            token = null
            label = ""
            lock.unlock()
        }
    }
}
