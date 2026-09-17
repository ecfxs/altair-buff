package com.altair.probe

import android.content.Context

/**
 * 全局核心单例。
 *
 * 解决的问题：Activity 与悬浮窗 Service 都需要截图/发键/自更新，
 * 若各自 new 一个 [RootShell] 就会开两个 `su` 进程，既浪费又可能互相干扰。
 * 这里统一持有**唯一一个**常驻 root shell，两边共用。
 *
 * 注意：[RootShell.open] 有超时保护，多次调用是安全的；
 * [ensureRoot] 只在未建立时才建立。
 */
object ShellCore {

    /** 全局唯一的常驻 root shell。 */
    val root = RootShell()

    private var appCtx: Context? = null

    fun init(c: Context) {
        if (appCtx == null) appCtx = c.applicationContext
    }

    private fun ctx(): Context =
        appCtx ?: throw IllegalStateException("ShellCore.init() 未调用")

    /** 探测逻辑（两个界面共用）。 */
    val probe: Probe by lazy { Probe(ctx(), root) { LogBus.emit(it) } }

    /** 自更新（两个界面共用）。 */
    val updater: Updater by lazy { Updater(ctx(), root) { LogBus.emit(it) } }

    /** 确保 root shell 已建立，返回是否可用。 */
    fun ensureRoot(): Boolean = if (root.isAlive) true else root.open()
}
