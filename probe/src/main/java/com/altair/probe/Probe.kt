package com.altair.probe

import android.content.Context

/**
 * 触摸执行、前台检查与 Root 授权。普通命令与触摸分别使用独立进程。
 */
class Probe(
    private val ctx: Context,
    /** 由外部注入的串行 Root 命令通道。 */
    private val sh: RootShell,
    private val log: (String) -> Unit
) {

    fun requestRoot(): String {
        sh.close()
        val ok = sh.open(15_000)
        return if (ok) "Root 已授权：${sh.lastHandshakeOutput}\n触摸将使用独立执行进程。"
            else sh.lastError
    }

    fun ensureShell(): Boolean = sh.isAlive || sh.open()

    /**
     * 单个进程执行整次动作；输出协议与退出码必须同时成功。
     *
     * @throws GestureSequence.ReleaseFailedException 触摸状态未知：agent 明确报告未确认松手，
     *   或输入进程在收尾窗口内没退出（可能还按着手指，只能强杀）。两者都必须阻止后续注入。
     */
    fun perform(args: List<String>, durationMs: Long, token: Actions.Token, geometry: ScreenGeometry) {
        token.check()
        check(ensureShell()) { "Root 不可用" }
        check(foregroundPackage() == OverlayService.targetPkgOf(ctx)) { "目标游戏不在前台" }
        check(ScreenGeometry.read(ctx) == geometry) { "屏幕方向或分辨率已改变，请重新标记" }
        token.check()
        val command = "CLASSPATH=${RootShell.quote(ctx.applicationInfo.sourceDir)} " +
            "app_process /system/bin com.altair.probe.TouchAgent " + args.joinToString(" ", transform = RootShell::quote)
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        TouchProcess.perform(process, durationMs, token, {
            check(ScreenGeometry.read(ctx) == geometry) { "屏幕发生变化，动作已停止" }
            check(foregroundPackage() == OverlayService.targetPkgOf(ctx)) { "已离开目标游戏，动作已停止" }
        }, log)
    }

    /**
     * 当前前台应用的包名；取不到返回 ""。
     *
     * 为什么需要它：所有输入动作都只在**目标游戏处于前台**时才允许执行。
     * 否则一旦焦点跑到桌面/系统弹窗/本应用上，点击和按键就会打到错误的地方 ——
     * 无人值守时这是很危险的一类事故。
     */
    fun foregroundPackage(): String {
        if (!ensureShell()) return ""
        // mCurrentFocus=Window{a1b2 u0 com.nexon.mod/com.nexon.mod.MainActivity}
        val a = sh.exec("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus", 1500)
        extractPkg(a)?.let { return it }
        // 退而求其次：看顶层 resumed activity
        val b = sh.exec(
            "dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'",
            1500
        )
        extractPkg(b)?.let { return it }
        return ""
    }

    /** 从窗口/活动信息里抠出包名。 */
    private fun extractPkg(line: String): String? {
        // 形如  u0 com.nexon.mod/com.nexon.mod.MainActivity
        val m = Regex("""([a-zA-Z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+""").find(line)
        return m?.groupValues?.get(1)
    }

}
