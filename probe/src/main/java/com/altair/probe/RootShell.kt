package com.altair.probe

import java.util.concurrent.TimeUnit

/**
 * 诊断 / 更新命令通道
 * ====================
 *
 * ## 为什么是"一次性"而不是常驻 shell
 * 常驻 `su` 进程需要自己处理提示符同步、管道残留和超时恢复，一旦客户端读串了行，
 * 后续每条命令都会错位（历史上正是这样把按键堵成 6 秒一次的）。这里改成**每条命令一个
 * `su -c` 进程**：慢一点，但每条命令的退出码、stderr 和超时都是确定的。
 *
 * ## 触摸为什么不走这里
 * 触摸由 [TouchAgent] 在**独立进程**里整段执行（见 [Probe.perform]）。
 * 如果和 `dumpsys` 这类查询共用一根管道，查询会把触摸事件之间的时间轴挤变形，
 * 而走位的 1:2:1 配比完全依赖时间准确。
 */
class RootShell @JvmOverloads constructor(
    private val launch: (String) -> Process = { ProcessBuilder("su", "-c", it).redirectErrorStream(true).start() }
) {
    /** 上一次 [open] 是否成功（uid=0 握手通过）。 */
    @Volatile var isAlive = false
        private set

    /** 握手输出或失败原因，界面「检查 Root」直接显示它。 */
    var lastHandshakeOutput = ""
        private set
    var lastError = ""
        private set

    data class Result(val exitCode: Int?, val output: String, val timedOut: Boolean) {
        /** 只有"正常退出且退出码为 0"才算成功 —— 超时绝不能被当成成功。 */
        val ok: Boolean get() = !timedOut && exitCode == 0
        fun text(): String = if (ok) output else
            "$output\n[${if (timedOut) "命令超时" else "退出码 $exitCode"}]".trim()
    }

    /** 建立通道：跑一次 `id` 并确认真的是 uid=0。 */
    @Synchronized fun open(timeoutMs: Long = 10_000): Boolean {
        if (isAlive) return true
        val result = try {
            execute("id", timeoutMs)
        } catch (e: java.io.IOException) {
            lastError = "无法启动 su：${e.message}"
            lastHandshakeOutput = lastError
            return false
        }
        lastHandshakeOutput = result.text()
        val ok = result.ok && Regex("""(?:^|\s)uid=0(?:\D|$)""").containsMatchIn(result.output)
        isAlive = ok
        lastError = if (ok) "" else "Root 不可用：${result.text().take(200)}"
        return ok
    }

    @Synchronized fun execute(cmd: String, timeoutMs: Long = 8000): Result {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val p = launch(cmd)
        val output = StringBuffer()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    r.forEachLine { line -> synchronized(output) {
                        if (output.length < 64_000) output.append(line.take(4000)).append('\n')
                    } }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) p.destroyForcibly()
            reader.join(300)
            return Result(if (done) p.exitValue() else null, output.toString().trim(), !done)
        } finally {
            if (p.isAlive) p.destroyForcibly()
            runCatching { p.outputStream.close() }
            runCatching { p.inputStream.close() }
        }
    }

    /** 执行并返回"输出 + 退出码说明"，界面直接展示。 */
    fun exec(cmd: String, timeoutMs: Long = 5000): String = execute(cmd, timeoutMs).text()

    @Synchronized fun close() { isAlive = false }

    companion object {
        /** 单引号转义，防止路径里的空格/分号/`$()` 被 shell 解释。 */
        fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
