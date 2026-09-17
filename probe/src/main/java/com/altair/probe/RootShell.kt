package com.altair.probe

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 常驻 root shell。
 *
 * ## 为什么必须常驻
 * 每次新建 `su -c "命令"` 进程的开销约 100~200ms。采样率是 1~5Hz，
 * 若每帧都新建进程，光是进程创建就会吃掉云手机大量 CPU。
 *
 * 正确做法：只开一次 `su`，之后所有命令写 stdin 即可。实测单次往返可降到 ~20ms。
 *
 * ## 输出切分
 * 交互式 shell 会把命令回显和结果混在一起。用「每条命令后 echo 一个唯一标记」
 * 的方式切分，读到标记即认为本条命令输出结束。
 */
class RootShell {

    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private var reader: Thread? = null
    private val lines = LinkedBlockingQueue<String>()
    private var seq = 0

    val isAlive: Boolean get() = proc?.isAlive == true

    /** 最近一次 open() 握手时 `id` 的原始输出（失败时是 su 的报错），用于诊断。 */
    var lastHandshakeOutput: String = ""
        private set

    /** 最近一次 open() 失败的原因。 */
    var lastError: String = ""
        private set

    /** 启动常驻 su 并握手校验。返回是否真的拿到了 root。 */
    fun open(timeoutMs: Long = 10000): Boolean {
        close()
        lastError = ""
        return try {
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            proc = p
            stdin = p.outputStream
            reader = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                        while (true) {
                            val l = br.readLine() ?: break
                            lines.offer(l)
                        }
                    }
                } catch (_: Throwable) {
                    // 进程被杀或流关闭，属正常退出路径
                } finally {
                    lines.offer(EOF)
                }
            }.also { it.isDaemon = true; it.start() }

            val out = exec("id", timeoutMs)
            lastHandshakeOutput = out
            val ok = out.contains("uid=0")
            if (!ok) lastError = if (out.isBlank()) "su 无任何输出（可能被拒绝或超时）" else out.take(200)
            ok
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            close()
            false
        }
    }

    /**
     * 在常驻 shell 里执行一条命令，返回其输出（不含标记行）。
     * 会自动追加 `2>&1` 合并 stderr。
     */
    fun exec(cmd: String, timeoutMs: Long = 5000): String = run(cmd, timeoutMs, appendErr = true)

    /**
     * 同 [exec]，但**不**追加 `2>&1`。
     * 用于需要自己精确控制重定向的命令，例如后台任务 `cmd >/dev/null 2>&1 &`——
     * 若末尾再被追加一个 `2>&1`，重定向顺序就乱了。
     */
    fun execRaw(cmd: String, timeoutMs: Long = 5000): String = run(cmd, timeoutMs, appendErr = false)

    private fun run(cmd: String, timeoutMs: Long, appendErr: Boolean): String {
        val out = stdin ?: return ""
        val marker = "__M${++seq}__"
        try {
            out.write((if (appendErr) "$cmd 2>&1\n" else "$cmd\n").toByteArray())
            out.write("echo $marker\n".toByteArray())
            out.flush()
        } catch (_: Throwable) {
            return ""
        }

        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) {
                sb.append("\n[命令超时 ${timeoutMs}ms]")
                break
            }
            val line = lines.poll(remain, TimeUnit.MILLISECONDS)
            if (line == null) {
                sb.append("\n[命令超时 ${timeoutMs}ms]")
                break
            }
            if (line == EOF) {
                sb.append("\n[shell 已退出]")
                break
            }
            if (line.trim() == marker) break
            sb.append(line).append('\n')
        }
        return sb.toString().trim('\n')
    }

    /** 执行并返回耗时（毫秒）。输出通过 [out] 回调。 */
    fun timedExec(cmd: String, timeoutMs: Long = 8000): Pair<Long, String> {
        val t0 = System.nanoTime()
        val r = exec(cmd, timeoutMs)
        val dt = (System.nanoTime() - t0) / 1_000_000
        return dt to r
    }

    fun close() {
        try { stdin?.close() } catch (_: Throwable) {}
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null
        stdin = null
        reader = null
        lines.clear()
    }

    companion object {
        private const val EOF = "\u0000__DSH_SHELL_EOF__"
    }
}
