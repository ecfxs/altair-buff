package com.altair.probe

import android.content.Context

/** Activity 与悬浮窗共用的配置上下文、串行命令通道和输入入口。 */
object ShellCore {

    /** 全局唯一的诊断/更新命令通道。 */
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
