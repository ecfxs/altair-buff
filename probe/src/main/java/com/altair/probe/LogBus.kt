package com.altair.probe

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局日志总线。
 *
 * ## 为什么每行都带时间戳
 * 这个工具的核心参数全是**时间**：两次补 BUFF 隔多久、走位和补 BUFF 之间有没有撞上、
 * 失败退避是不是真的等够了。没有时间戳时，日志只能回答"发生了什么事"，
 * 回答不了"隔了多久" —— 而那往往才是要查的那一项。
 * 远程排查时用户能给的只有这份日志，所以时间戳必须在生成端就打上。
 *
 * 格式用 `HH:mm:ss`：秒级足够读出"间隔 1 秒"这类结论，又不会把行首撑得太长。
 */
object LogBus {

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val buf = StringBuilder()
    private const val MAX = 400_000

    /** SimpleDateFormat 不是线程安全的，而 emit 任意线程可调用 —— 每个线程一份。 */
    private val stamp = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun add(l: (String) -> Unit) {
        listeners.add(l)
    }

    fun remove(l: (String) -> Unit) {
        listeners.remove(l)
    }

    /** 追加一行带时间戳的日志。任意线程可调用；回调在调用线程执行。 */
    fun emit(s: String) {
        val line = "${stamp.get()!!.format(Date())}  $s"
        synchronized(buf) {
            buf.append(line).append('\n')
            if (buf.length > MAX) buf.delete(0, buf.length - MAX / 2)
        }
        // 快照遍历，避免监听者在回调里增删导致并发问题
        listeners.toList().forEach { l ->
            runCatching { l(line) }
        }
    }

    /** 保留这个入口是为了不破坏既有调用点；时间戳现在由 [emit] 统一打。 */
    fun emitStamped(s: String) = emit(s)

    fun dump(): String = synchronized(buf) { buf.toString() }

    fun clear() = synchronized(buf) { buf.setLength(0) }
}
