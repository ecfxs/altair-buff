package com.altair.probe

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * root 通道 —— 双模式实现
 * ======================
 *
 * ## 背景：为什么需要两种模式
 * 最初只用了「常驻交互式 su」一种方式，理由是它快：
 *   - 常驻 shell：单次命令 **6~8ms**
 *   - 每次新建 `su -c`：单次命令 **22~30ms**
 *
 * 但实测发现红手指这台机器的 su 是**容器框架自定义的**
 * （`/acct/.mci/bin/su -> bpfdomain`），它只实现了 `su -c <命令>`；
 * 无参数启动想进交互 shell 时会因为没有 TTY **立即退出**（实测 1ms 就 EOF）。
 *
 * 这个现象很迷惑人：`su -c id` 明明返回 uid=0，但常驻 shell 建不起来 ——
 * 看起来像「root 权限丢了」，其实权限一直是好的。
 *
 * ## 因此改为双模式 + 自动降级
 *   1. 先试 **PERSISTENT**（常驻 shell），成功就用它，最快
 *   2. 失败则退到 **ONESHOT**（每次 `su -c`），慢约 20ms 但兼容性极好
 *   3. 运行中常驻 shell 若意外死掉，也会自动降级，而不是直接罢工
 *
 * 20~30ms 的开销在本项目里可以忽略 —— 截图本身就要 200~275ms。
 */
class RootShell {

    enum class Mode { NONE, PERSISTENT, ONESHOT }

    /** 当前生效的模式。 */
    var mode: Mode = Mode.NONE
        private set

    val modeName: String
        get() = when (mode) {
            Mode.PERSISTENT -> "常驻 shell（快，~8ms/命令）"
            Mode.ONESHOT -> "su -c 单次调用（兼容模式，~25ms/命令）"
            Mode.NONE -> "未建立"
        }

    /** 最近一次 open() 握手时 `id` 的原始输出（失败时是 su 的报错），用于诊断。 */
    var lastHandshakeOutput: String = ""
        private set

    /** 最近一次 open() 失败的原因。 */
    var lastError: String = ""
        private set

    // ---- 常驻模式的状态 ----
    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private var reader: Thread? = null
    private val lines = LinkedBlockingQueue<String>()
    private var seq = 0

    /** 通道是否可用。ONESHOT 模式下只要验证过就算可用。 */
    val isAlive: Boolean
        get() = when (mode) {
            Mode.PERSISTENT -> proc?.isAlive == true
            Mode.ONESHOT -> true
            Mode.NONE -> false
        }

    // ------------------------------------------------------------ 打开

    /**
     * 建立 root 通道。先试常驻，失败自动降级为单次调用。
     * [timeoutMs] 用于每步握手；因为可能要等用户点授权框，建议给足（20~25 秒）。
     */
    fun open(timeoutMs: Long = 10000): Boolean {
        close()
        lastError = ""

        // 1) 常驻 shell
        if (tryPersistent(timeoutMs)) {
            mode = Mode.PERSISTENT
            return true
        }

        // 2) 降级：每次 su -c
        val one = execOneShot("id", maxOf(timeoutMs, 15000))
        lastHandshakeOutput = one
        if (one.contains("uid=0")) {
            mode = Mode.ONESHOT
            lastError = "常驻 shell 不可用（该设备的 su 不支持交互模式），已自动降级为 su -c"
            return true
        }

        mode = Mode.NONE
        if (lastError.isBlank()) {
            lastError = if (one.isBlank()) "su -c 无输出（可能被拒绝或超时）" else one.take(200)
        }
        return false
    }

    private fun tryPersistent(timeoutMs: Long): Boolean {
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            proc = p
            stdin = p.outputStream
            lines.clear()
            reader = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                        while (true) {
                            val l = br.readLine() ?: break
                            lines.offer(l)
                        }
                    }
                } catch (_: Throwable) {
                    // 进程被杀或流关闭，走正常退出路径
                } finally {
                    lines.offer(EOF)
                }
            }.also { it.isDaemon = true; it.start() }

            val out = execPersistent("id", timeoutMs)
            val ok = out.contains("uid=0")
            if (ok) {
                lastHandshakeOutput = out
            } else {
                lastError = "常驻 shell 握手失败: " +
                    (if (out.isBlank()) "(无输出)" else out.replace("\n", " ").take(120))
                closePersistent()
            }
            ok
        } catch (t: Throwable) {
            lastError = "常驻 shell 启动异常: ${t.javaClass.simpleName}: ${t.message}"
            closePersistent()
            false
        }
    }

    // ------------------------------------------------------------ 执行

    /**
     * 执行命令。自动追加 `2>&1` 合并 stderr。
     * 常驻模式中途死掉时会**自动降级**到单次调用并重试一次，上层无需感知。
     */
    fun exec(cmd: String, timeoutMs: Long = 5000): String = run(cmd, timeoutMs, appendErr = true)

    /**
     * 同 [exec]，但**不**追加 `2>&1`。
     * 用于需要精确控制重定向的命令（如后台任务 `cmd >/dev/null 2>&1 &`），
     * 否则末尾再被追加一个 `2>&1` 会打乱重定向顺序。
     */
    fun execRaw(cmd: String, timeoutMs: Long = 5000): String = run(cmd, timeoutMs, appendErr = false)

    private fun run(cmd: String, timeoutMs: Long, appendErr: Boolean): String {
        val real = if (appendErr) "$cmd 2>&1" else cmd
        return when (mode) {
            Mode.PERSISTENT -> {
                val r = execPersistent(real, timeoutMs)
                if (r.contains("[shell 已退出]")) {
                    lastError = "常驻 shell 中途退出，已自动降级为 su -c 模式"
                    mode = Mode.ONESHOT
                    closePersistent()
                    execOneShot(real, timeoutMs)
                } else {
                    r
                }
            }
            Mode.ONESHOT -> execOneShot(real, timeoutMs)
            Mode.NONE -> ""
        }
    }

    /** 执行并返回 (耗时毫秒, 输出)。 */
    fun timedExec(cmd: String, timeoutMs: Long = 8000): Pair<Long, String> {
        val t0 = System.nanoTime()
        val r = exec(cmd, timeoutMs)
        return (System.nanoTime() - t0) / 1_000_000 to r
    }

    // ---- 常驻模式实现 ----

    private fun execPersistent(cmd: String, timeoutMs: Long): String {
        val out = stdin ?: return ""
        val marker = "__M${++seq}__"
        try {
            out.write("$cmd\n".toByteArray())
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

    // ---- 单次调用模式实现 ----

    /**
     * `su -c "<cmd>"`。读数放在独立线程，主线程只做带超时的 waitFor。
     * **绝不能用 readText() + 无超时 waitFor()** —— 某些 su 会卡住不返回，那样整个 App 就挂了。
     */
    private fun execOneShot(cmd: String, timeoutMs: Long): String {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val t = Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') }
                }
            }
            t.isDaemon = true
            t.start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                runCatching { p.destroy() }
                sb.append("\n[命令超时 ${timeoutMs}ms]")
            } else {
                t.join(300)
            }
            sb.toString().trim('\n')
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    // ------------------------------------------------------------ 关闭

    private fun closePersistent() {
        try { stdin?.close() } catch (_: Throwable) {}
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null
        stdin = null
        reader = null
        lines.clear()
    }

    fun close() {
        closePersistent()
        mode = Mode.NONE
    }

    companion object {
        private const val EOF = "\u0000__DSH_SHELL_EOF__"
    }
}
