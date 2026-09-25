package com.altair.probe

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * 触摸执行、前台检查与 Root 授权。普通命令与触摸分别使用独立进程。
 */
class Probe(
    private val ctx: Context,
    /** 由外部注入的串行 Root 命令通道。 */
    private val sh: RootShell,
    private val log: (String) -> Unit
) {
    private companion object {
        /**
         * 注入过程中的安全复查间隔。
         *
         * 每次复查要跑一条 `dumpsys window`（本身就要几百毫秒），所以**不能**按 100ms 跑 ——
         * 那会把常驻通道占满。一次走位约 4 秒，1 秒一次的复查足够在离开游戏时及时刹车。
         */
        const val RECHECK_MS = 1000L
    }

    fun requestRoot(): String {
        sh.close()
        val ok = sh.open(15_000)
        return if (ok) "Root 已授权：${sh.lastHandshakeOutput}\n触摸将使用独立执行进程。"
            else sh.lastError
    }

    fun ensureShell(): Boolean = sh.isAlive || sh.open()

    /** 单个进程执行整次动作；输出协议与退出码必须同时成功。 */
    fun perform(args: List<String>, durationMs: Long, token: Actions.Token, geometry: ScreenGeometry) {
        token.check()
        check(ensureShell()) { "Root 不可用" }
        check(foregroundPackage() == OverlayService.targetPkgOf(ctx)) { "目标游戏不在前台" }
        check(ScreenGeometry.read(ctx) == geometry) { "屏幕方向或分辨率已改变，请重新标记" }
        token.check()
        val command = "CLASSPATH=${RootShell.quote(ctx.applicationInfo.sourceDir)} " +
            "app_process /system/bin com.altair.probe.TouchAgent " + args.joinToString(" ", transform = RootShell::quote)
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuffer()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { r ->
                    r.forEachLine { line -> synchronized(output) {
                        if (output.length < 8000) output.append(line.take(1000)).append('\n')
                    } }
                }
            }
        }.apply { isDaemon = true; start() }
        // 关闭 stdin 是取消协议，也覆盖宿主进程死亡。不能先杀子进程，否则无法发送 UP。
        token.attach { runCatching { process.outputStream.close() } }
        try {
            token.check()
            process.outputStream.write("GO\n".toByteArray())
            process.outputStream.flush()
            val deadline = android.os.SystemClock.elapsedRealtime() + durationMs + 10_000
            var nextCheck = 0L
            while (!process.waitFor(40, TimeUnit.MILLISECONDS)) {
                token.check()
                val now = android.os.SystemClock.elapsedRealtime()
                check(now < deadline) { "输入执行超时" }
                if (now >= nextCheck) {
                    check(ScreenGeometry.read(ctx) == geometry) { "屏幕发生变化，动作已停止" }
                    check(foregroundPackage() == OverlayService.targetPkgOf(ctx)) { "已离开目标游戏，动作已停止" }
                    nextCheck = now + RECHECK_MS
                }
            }
            reader.join(300)
            val text = output.toString()
            text.lineSequence().filter { it.startsWith("TOUCH_RELEASE") }.forEach { log(it) }
            token.check()
            check(process.exitValue() == 0 && text.lineSequence().any { it == "TOUCH_OK" }) {
                "输入未完成（退出码 ${process.exitValue()}）：${text.trim().take(400)}"
            }
        } finally {
            runCatching { process.outputStream.close() }
            val interrupted = Thread.interrupted()
            try {
                if (!process.waitFor(2000, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    LogBus.emit("输入进程未确认松手，已终止；请检查游戏触摸状态")
                }
            } finally {
                token.attach(null)
                runCatching { process.inputStream.close() }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
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
