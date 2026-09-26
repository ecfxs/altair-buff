package com.altair.probe

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 触摸进程协议与收尾；独立于 Android，供真实子进程回归测试使用。 */
object TouchProcess {
    @JvmOverloads
    fun perform(process: Process, durationMs: Long, token: Actions.Token,
                checkSafety: () -> Unit, log: (String) -> Unit, cleanupMs: Long = 2000L) {
        val output = StringBuffer()
        val idle = AtomicBoolean()
        val ok = AtomicBoolean()
        val releaseFailure = AtomicReference<String?>()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line == "TOUCH_IDLE") idle.set(true)
                        if (line == "TOUCH_OK") ok.set(true)
                        if (line.startsWith("TOUCH_RELEASE_FAILED")) releaseFailure.set(line)
                        synchronized(output) {
                            output.append(line.take(1000)).append('\n')
                            if (output.length > 8000) output.delete(0, output.length - 8000)
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        token.attach { runCatching { process.outputStream.close() } }
        var started = false
        var primary: Throwable? = null
        var forced = false
        var interrupted = false
        try {
            token.check()
            started = true
            process.outputStream.write("GO\n".toByteArray())
            process.outputStream.flush()
            val deadline = System.nanoTime() + (durationMs + 10_000) * 1_000_000
            var nextCheck = 0L
            while (!process.waitFor(40, TimeUnit.MILLISECONDS)) {
                token.check()
                val now = System.nanoTime()
                check(now < deadline) { "输入执行超时" }
                if (now >= nextCheck) {
                    checkSafety()
                    nextCheck = now + 1_000_000_000
                }
            }
        } catch (e: Throwable) {
            primary = e
        } finally {
            runCatching { process.outputStream.close() }
            interrupted = Thread.interrupted()
            try {
                if (!process.waitFor(cleanupMs, TimeUnit.MILLISECONDS)) {
                    forced = true
                    process.destroyForcibly()
                }
                reader.join(300)
                // 子进程已退出但输出还没排空时，`idle` 可能只是"还没读到"。
                // 再等一小段，避免把干净运行误判成"未确认松手"而误锁注入。
                if (reader.isAlive) reader.join(1_000)
            } catch (e: Exception) {
                if (e is InterruptedException) interrupted = true
                forced = true
                process.destroyForcibly()
                if (primary == null) primary = e else primary.addSuppressed(e)
            } finally {
                token.attach(null)
                runCatching { process.inputStream.close() }
            }
        }
        val text = output.toString()
        text.lineSequence().filter { it.startsWith("TOUCH_") && it != "TOUCH_OK" && it != "TOUCH_IDLE" }
            .forEach { line -> runCatching { log(line) } }
        // 所有退出路径（包括取消和门禁失败）都必须在收尾之后检查回执。
        val unknown = releaseFailure.get() ?: if (started && (forced || reader.isAlive || !idle.get()))
            "输入进程未确认触摸已释放，请检查游戏触摸状态" else null
        if (unknown != null) {
            val failure = GestureSequence.ReleaseFailedException(unknown, null)
            if (primary == null) primary = failure else primary.addSuppressed(failure)
        }
        if (interrupted) Thread.currentThread().interrupt()
        primary?.let { throw it }
        token.check()
        check(process.exitValue() == 0 && ok.get()) {
            "输入未完成（退出码 ${process.exitValue()}）：${text.trim().take(400)}"
        }
    }
}
