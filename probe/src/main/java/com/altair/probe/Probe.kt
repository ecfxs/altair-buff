package com.altair.probe

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * P0 底层通道：触摸输入（点击 / 摇杆）、前台应用检测、root 申请与截图。
 */
class Probe(
    private val ctx: Context,
    /** 由外部注入的常驻 root shell —— Activity 与悬浮窗共用同一个 su 进程。 */
    private val sh: RootShell,
    private val log: (String) -> Unit
) {
    // 供 UI 与结论使用
    var rootOk = false; private set
    var bestDisplayId = -1; private set

    private val cache: File get() = ctx.cacheDir

    /**
     * 安全地跑一次性外部命令：读取放到独立线程，主线程只 waitFor 带超时。
     * **绝不用 readText()+waitFor() 无超时的写法** —— 某些 su 会卡住不返回，那样整个 App 就挂了。
     */
    private fun tryOneShot(cmd: List<String>, timeoutMs: Long): String {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = StringBuilder()
            val t = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
            }
            t.isDaemon = true
            t.start()
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                runCatching { p.destroy() }
                "超时（${timeoutMs}ms 内未返回）"
            } else {
                out.toString().trim().ifEmpty { "(无输出，退出码 ${p.exitValue()})" }
            }
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * 申请 / 重新检测 root 权限。
     *
     * 为什么需要单独的按钮：`pm install -r` 更新 APK 会杀掉本进程并重启，
     * **su 的授权会话很可能随之失效** —— 表现为「系统里明明给了 root，但 App 拿不到」。
     * 这个按钮会关掉旧 shell、重新拉起 su（若系统弹授权框就趁机点允许），
     * 并把每一步的原始输出报出来，失败时能直接看到 su 说了什么。
     */
    fun requestRoot(): String {
        val sb = StringBuilder()
        sb.append("=== 申请 / 检测 root 权限 ===\n")

        // 1) 枚举候选 su 路径（不同云手机方案 su 位置不同）
        val cands = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su",
            "/acct/.mci/bin/su", "/system/xbin/daemonsu"
        )
        sb.append("候选 su 路径:\n")
        val found = cands.filter { File(it).exists() }
        for (c in cands) sb.append("  ${if (File(c).exists()) "✅" else "— "} $c\n")
        if (found.isEmpty()) sb.append("  ⚠ 常见路径下都没找到 su 文件\n")

        // 2) 一次性 `su -c id`：这条会触发系统的授权询问
        sb.append("\n[1] 一次性 su -c id（若系统弹授权框，请点「允许」）\n")
        sb.append("    ").append(tryOneShot(listOf("su", "-c", "id"), 25_000)).append('\n')

        // 3) 候选路径逐个试（有些机器的 su 不在 PATH 里）
        for (c in found) {
            if (c == "/system/xbin/su") continue
            sb.append("\n[2] $c -c id\n")
            sb.append("    ").append(tryOneShot(listOf(c, "-c", "id"), 15_000)).append('\n')
        }

        // 4) 重新建立常驻 shell
        sb.append("\n[3] 重建常驻 shell（主通道）\n")
        sh.close()
        val t0 = System.nanoTime()
        val ok = sh.open(25_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        rootOk = ok
        sb.append("    结果: ").append(if (ok) "成功 ✅" else "失败 ❌").append("  (${ms}ms)\n")
        if (ok) {
            sb.append("    生效模式: ").append(sh.modeName).append('\n')
            sb.append("    id 输出: ").append(sh.lastHandshakeOutput.replace("\n", " ").trim()).append('\n')
            val (rt, _) = sh.timedExec("id", 5000)
            sb.append("    单次命令往返: ${rt}ms\n")
            if (sh.mode == com.altair.probe.RootShell.Mode.ONESHOT) {
                sb.append(
                    """
                    |
                    |    说明：本机的 su 不支持交互式常驻 shell（无 TTY 时立即退出），
                    |          已自动降级为 `su -c` 单次调用模式。
                    |          慢约 20ms/命令，但截图本身要 200~275ms，影响可忽略。
                    |          **root 权限本身是正常的**，不需要再折腾授权。
                    |""".trimMargin()
                )
            }
        } else {
            sb.append("    su 原始输出: ").append(sh.lastHandshakeOutput.ifBlank { "(空)" }).append('\n')
            sb.append("    失败原因: ").append(sh.lastError.ifBlank { "(未知)" }).append('\n')
            sb.append(
                """
                |
                |=== 失败时怎么办 ===
                |  · 确认红手指客户端里的 root 开关是打开的
                |  · 看云手机屏幕上有没有弹授权框 —— 有就点「允许」，然后**重新点一次本按钮**
                |  · 部分云手机更新 App 后需要重新授权，这是已知现象
                |""".trimMargin()
            )
        }
        return sb.toString()
    }

    /** 确保常驻 root shell 已建立（并同步 rootOk，供只走悬浮窗、没跑过完整探测的场景）。 */
    fun ensureShell(): Boolean {
        if (sh.isAlive) { rootOk = true; return true }
        rootOk = sh.open()
        return rootOk
    }

    /**
     * 悬浮窗用的快速截图：报告分辨率与方向，用来确认「画面截得到吗」。
     */
    fun quickCapture(): String {
        if (!ensureShell()) return "无 root"
        val d = if (bestDisplayId >= 0) bestDisplayId else 0
        val f = File(cache, "quick.raw")
        f.delete()
        val (ms, err) = sh.timedExec("screencap -d $d ${f.absolutePath}", 12000)
        val sb = StringBuilder()
        val hdr = parseRawHeader(f)
        if (hdr == null) {
            sb.append("raw 截图失败 ${ms}ms  ${err.take(60)}")
            return sb.toString()
        }
        val w = hdr[0]; val h = hdr[1]
        sb.append("截图 ${w}x${h} ${if (w > h) "横屏（与游戏一致）" else "竖屏 ⚠ 游戏可能不在前台"}  ${ms}ms\n")
        return sb.toString()
    }

    /**
     * 在归一化坐标处点击。
     *
     * 每次都先截一帧拿到**当前真实方向**的尺寸（横屏 1280x720 / 竖屏 720x1280），
     * 这样归一化坐标换算永远正确，不会因方向变化而点偏。
     * 代价是每次多约 200ms —— 手动测试完全可接受。
     */
    fun tapNorm(
        nx: Double, ny: Double,
        label: String = "",
        pressMs: Int = 90,
        method: String = "swipe"
    ): String {
        if (!ensureShell()) return "无 root"
        val d = if (bestDisplayId >= 0) bestDisplayId else 0
        val ref = File(cache, "tapref.raw")
        ref.delete()
        sh.timedExec("screencap -d $d ${ref.absolutePath}", 12000)
        val hdr = parseRawHeader(ref)
        val w = hdr?.get(0) ?: 1280
        val h = hdr?.get(1) ?: 720
        val px = (nx * w).toInt().coerceIn(0, w - 1)
        val py = (ny * h).toInt().coerceIn(0, h - 1)

        // ★ 关键：不要用 `input tap`。
        // tap 发出的 DOWN/UP 时间戳几乎相同（0ms），很多游戏有「最短按压时长」判定，
        // 会把这种 0ms 点击当成无效输入直接丢弃。
        // `input swipe x y x y D` 是同一个点出发再回到同一个点，能精确控制按住 D 毫秒，
        // 这才是游戏认得的「真实点击」。
        // 两种按压实现，任选：
        //   swipe       —— 同点滑动，靠 duration 控制按压时长（默认）
        //   motionevent —— 显式 DOWN / sleep / UP，控制最精确，
        //                  但 input motionevent 只在较新 Android 上可用
        val cmd = when (method) {
            "motionevent" ->
                "input motionevent DOWN $px $py; sleep ${"%.2f".format(pressMs / 1000.0)}; input motionevent UP $px $py"
            "tap" -> "input tap $px $py"
            else -> "input swipe $px $py $px $py $pressMs"
        }
        val (ms, err) = sh.timedExec(cmd, 8000)
        val tag = if (label.isBlank()) "" else "[$label] "
        val nx4 = "%.4f".format(nx)
        val ny4 = "%.4f".format(ny)
        // 注意：Kotlin 允许中文作标识符，所以 "$tag点击" 会被当成变量名 —— 必须加花括号
        return "${tag}点击 ($px, $py)  归一化($nx4, $ny4)  画面 ${w}x${h}  方式=$method 按压 ${pressMs}ms  ${ms}ms  ${err.take(40)}"
    }

    /**
     * 摇杆走一步：在摇杆中心按下 → 拖到偏移点 → **保持** holdMs → 松手。
     *
     * 为什么不用方向键：用户实测 `input keyevent 21/22` 走位不好用
     * （游戏大概没把移动绑在方向键上）。左下角摇杆是游戏自己的移动入口，最可靠。
     *
     * 手势语义是这里的关键 —— 摇杆要的是「按住不放」：
     *   DOWN 中心 → MOVE 偏移点 → sleep 保持 → UP
     * 而 `input swipe` 的拖动过程本身占满整个 duration（线性插值），
     * 角色是在"拖动过程中"走的，所以兜底用 swipe 时时长就等于要走动的时长。
     *
     * 优先 motionevent（能精确表达"按住不动"这段），不支持它的旧系统自动退回 swipe。
     */
    fun joystickWalk(
        centerX: Double, centerY: Double,   // 归一化：摇杆中心
        moveX: Double, moveY: Double,       // 归一化：推到该点（=方向与幅度）
        holdMs: Int,
        method: String = "motionevent",
    ): String {
        if (!ensureShell()) return "无 root"
        val d = if (bestDisplayId >= 0) bestDisplayId else 0
        val ref = File(cache, "joy.raw")
        ref.delete()
        sh.timedExec("screencap -d $d ${ref.absolutePath}", 12000)
        val hdr = parseRawHeader(ref)
        val w = hdr?.get(0) ?: 1280
        val h = hdr?.get(1) ?: 720
        val cx = (centerX * w).toInt().coerceIn(0, w - 1)
        val cy = (centerY * h).toInt().coerceIn(0, h - 1)
        val mx = (moveX * w).toInt().coerceIn(0, w - 1)
        val my = (moveY * h).toInt().coerceIn(0, h - 1)
        fun swipeCmd() = "input swipe $cx $cy $mx $my $holdMs"

        // ★ 保持期间必须**持续补发 MOVE**，这是走位能不能动的关键。
        //
        //   游戏引擎在每次 MotionEvent 到来时才更新摇杆的方向与幅度。只发一次 MOVE 然后 sleep，
        //   中间这段时间没有任何事件 —— 摇杆等于被"点"了一下就松开，角色几乎不动。
        //   所以要保持，就必须把 MOVE 铺满整个 holdMs。
        //
        //   但每次 `input` 都要起一个 app_process（实测单条 ~30ms），帧数不能随便加：
        //   2400ms 若按 12ms 一帧要 200 条，光进程开销就 6 秒，整段手势被撑到 8 秒以上。
        //   这里取**有界**帧数，并把每次的 ~30ms 固有开销从 sleep 里扣掉，
        //   使整段手势的墙钟时长仍然 ≈ holdMs（否则 1:2:1 的对称性会被开销撑坏）。
        val perCallMs = 30L
        val frames = (holdMs / 120L).coerceIn(2L, 20L).toInt()
        val sleepMs = (holdMs - frames * perCallMs).coerceAtLeast(0L) / frames
        fun meCmd(): String = buildString {
            append("input motionevent DOWN $cx $cy; ")
            append("input motionevent MOVE $mx $my; ")
            if (sleepMs > 0) {
                val s = "%.3f".format(sleepMs / 1000.0)
                repeat(frames) { append("sleep $s; input motionevent MOVE $mx $my; ") }
            }
            append("input motionevent UP $mx $my")
        }

        // 明确的 swipe 档位直接走兜底
        if (method == "swipe") {
            val (ms, err) = sh.timedExec(swipeCmd(), (8000 + holdMs).toLong())
            return "摇杆(swipe) ($cx,$cy)→($mx,$my) ${holdMs}ms  ${ms}ms ${err.take(40)}"
        }

        // 超时要把串行的 input 开销算进去，否则会被中途掐断
        // （表现是"走到一半突然松手"，而且日志里看不出是超时）。
        // 命令构成：DOWN + MOVE + (sleep + MOVE) × frames + UP
        val inputCount = 2 + frames + 1
        val meTimeout = 4000L + holdMs + inputCount * perCallMs
        val (ms, err) = sh.timedExec(meCmd(), meTimeout)
        val failed = err.contains("not found", true) || err.contains("Unknown", true) ||
            err.contains("Error", true) || err.contains("inaccessible", true)
        if (!failed) {
            return "摇杆(motionevent) ($cx,$cy)→($mx,$my) 保持 ${holdMs}ms（${frames} 帧）" +
                "  ${ms}ms ${err.take(40)}"
        }
        // 该机型的 input 没有 motionevent 子命令 → 退回 swipe
        val (ms2, err2) = sh.timedExec(swipeCmd(), (8000 + holdMs).toLong())
        return "摇杆(swipe兜底，motionevent 不可用) ($cx,$cy)→($mx,$my) ${holdMs}ms  ${ms2}ms ${err2.take(40)}"
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
        val a = sh.exec("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus", 6000)
        extractPkg(a)?.let { return it }
        // 退而求其次：看顶层 resumed activity
        val b = sh.exec(
            "dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'",
            6000
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

    /** 解析 screencap raw 文件头，返回 [w, h, format, colorspace, headerBytes]。 */
    private fun parseRawHeader(f: File): IntArray? {
        if (!f.exists() || f.length() < 16) return null
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val b = ByteArray(16)
                raf.readFully(b)
                fun u32(o: Int) = (b[o].toInt() and 0xFF) or
                    ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16) or
                    ((b[o + 3].toInt() and 0xFF) shl 24)
                val w = u32(0); val h = u32(4); val fmt = u32(8); val cs = u32(12)
                if (w !in 1..20000 || h !in 1..20000) return null
                val exp16 = 16L + w.toLong() * h * 4
                val exp12 = 12L + w.toLong() * h * 4
                val hb = when (f.length()) {
                    exp16 -> 16
                    exp12 -> 12
                    else -> 16
                }
                intArrayOf(w, h, fmt, cs, hb)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 释放常驻 root shell。 */
    fun close() {
        try { sh.close() } catch (_: Throwable) {}
    }
}
