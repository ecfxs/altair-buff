package com.altair.probe

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局日志总线。
 *
 * 为什么需要它：Activity 与悬浮窗 Service 都想显示同一份日志，
 * 而探测/测试逻辑在两个界面里都可能被触发。用一根总线分发，
 * 谁在监听谁就能显示，且缓冲区保留全部输出供「复制报告」使用。
 */
object LogBus {

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val buf = StringBuilder()
    private const val MAX = 400_000

    fun add(l: (String) -> Unit) {
        listeners.add(l)
    }

    fun remove(l: (String) -> Unit) {
        listeners.remove(l)
    }

    /** 追加一行日志。任意线程可调用；回调在调用线程执行。 */
    fun emit(s: String) {
        synchronized(buf) {
            buf.append(s).append('\n')
            if (buf.length > MAX) buf.delete(0, buf.length - MAX / 2)
        }
        // 快照遍历，避免监听者在回调里增删导致并发问题
        listeners.toList().forEach { l ->
            runCatching { l(s) }
        }
    }

    /** 追加一行并自动补时间戳。长耗时操作（更新/检查）的**结果**用它，便于在日志里定位时刻。 */
    fun emitStamped(s: String) {
        emit("[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $s")
    }

    fun dump(): String = synchronized(buf) { buf.toString() }

    fun clear() = synchronized(buf) { buf.setLength(0) }
}
