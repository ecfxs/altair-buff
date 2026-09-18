package com.altair.probe

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * P0 探测逻辑。
 *
 * 目标是在红手指云手机上实测出设计文档第 14 章列的三项运行期未知数：
 *   R1 root 真伪与权限范围
 *   R2 screencap 是否黑屏 / 真实 displayId
 *   R3 截图与按键的往返时延
 *
 * 所有探测都只读或只写自己 cache 目录，除「测试按键」外无副作用。
 */
class Probe(
    private val ctx: Context,
    /** 由外部注入的常驻 root shell —— Activity 与悬浮窗共用同一个 su 进程。 */
    private val sh: RootShell,
    private val log: (String) -> Unit
) {
    private val rep = StringBuilder()

    // 供 UI 与结论使用
    var rootOk = false; private set
    var bestDisplayId = -1; private set
    var captureMedianMs = -1L; private set
    var captureP90Ms = -1L; private set
    var frameVariance = 0.0; private set
    var frameEntropy = 0.0; private set
    var blackScreen = false; private set
    var displayCount = 0; private set

    private val cache: File get() = ctx.cacheDir

    /** 暴露常驻 shell 给自更新模块复用，避免开第二个 su 进程。 */
    val shell: RootShell get() = sh

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

    // ------------------------------------------------------------ 输出辅助

    private fun out(s: String = "") { rep.append(s).append('\n'); log(s) }
    private fun sec(t: String) { out(); out("======== $t ========") }
    private fun kv(k: String, v: Any?) = out(pad(k, 26) + (v?.toString() ?: "-"))
    private fun pad(s: String, n: Int) = if (s.length >= n) s else s + " ".repeat(n - s.length)

    // ------------------------------------------------------------ 主流程

    fun run(): String {
        rep.setLength(0)
        try {
            sec("0. 设备与环境"); device()
            sec("1. root 探测"); root()
            sec("2. 命令可用性"); commands()
            sec("3. 显示枚举"); displays()
            sec("4. 截图黑屏自检与时延"); captureTest()
            sec("5. 输入时延"); inputTest()
            sec("6. 保活环境"); keepAlive()
            sec("7. 结论与建议"); verdict()
        } catch (t: Throwable) {
            out("!! 探测过程中出现异常: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            sh.close()
        }
        return rep.toString()
    }

    // ------------------------------------------------------------ 0 设备

    @SuppressLint("DiscouragedApi", "DEPRECATED")
    private fun device() {
        kv("Android 版本", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        kv("厂商 / 型号", "${Build.MANUFACTURER} / ${Build.MODEL}")
        kv("device / board", "${Build.DEVICE} / ${Build.BOARD}")
        kv("CPU ABI", Build.SUPPORTED_ABIS.joinToString(", "))
        kv("CPU 核数", Runtime.getRuntime().availableProcessors())
        kv("总内存", "${Runtime.getRuntime().maxMemory() / 1024 / 1024} MB (heap 上限)")

        val dm = DisplayMetrics()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        kv("真实分辨率", "${dm.widthPixels} x ${dm.heightPixels}")
        kv("densityDpi", dm.densityDpi)
        kv("屏幕方向", if (dm.widthPixels >= dm.heightPixels) "横屏" else "竖屏")

        // 读 /proc/cpuinfo 的型号行
        try {
            val info = File("/proc/cpuinfo").readLines()
            val model = info.firstOrNull { it.startsWith("Hardware") || it.startsWith("model name") }
            if (model != null) kv("CPU 型号", model.substringAfter(':').trim())
        } catch (_: Throwable) {}

        kv("本应用包名", ctx.packageName)
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            kv("targetSdk", pi.applicationInfo?.targetSdkVersion ?: -1)
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------ 1 root

    private fun root() {
        // 1) su 可执行文件是否存在 —— 纯文件检查，零阻塞风险
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su"
        )
        val found = paths.filter { File(it).exists() }
        kv("su 文件", if (found.isEmpty()) "常见路径均未找到" else found.joinToString(", "))

        // 2) 常驻 shell 握手。RootShell 内部全程走队列 + 超时，不会挂住。
        val t0 = System.nanoTime()
        rootOk = sh.open()
        val openMs = (System.nanoTime() - t0) / 1_000_000
        kv("常驻 shell 建立", (if (rootOk) "成功" else "失败") + " (${openMs}ms)")

        if (!rootOk) {
            out("→ root 不可用。请检查红手指客户端的 root 开关是否已打开。")
            return
        }

        kv("root 通道模式", sh.modeName)
        val (ms, idOut) = sh.timedExec("id", 5000)
        kv("id 输出", idOut.replace("\n", " ").trim())
        kv("单次命令往返", "${ms}ms   ← 主通道的实际开销")

        // 嵌套跑一次一次性 su，作为「每帧新建进程」的代价对照
        val (spawnMs, spawnOut) = sh.timedExec("su -c id", 8000)
        kv("一次性 su -c id 耗时", "${spawnMs}ms   ← 对照：这是每帧新建进程的代价")
        kv("  └ 输出", spawnOut.replace("\n", " ").trim().ifEmpty { "(空)" })

        kv("SELinux", sh.exec("getenforce", 3000).trim())
        kv("su 路径", sh.exec("which su", 3000).trim())
        kv("su 权限", sh.exec("ls -l \$(which su 2>/dev/null)", 3000).trim())
        kv("可写 /data/local/tmp", sh.exec("touch /data/local/tmp/__p && echo YES || echo NO", 3000).trim())
        val caps = sh.exec("grep -i '^Cap' /proc/self/status", 3000).replace("\n", " | ").trim()
        if (caps.isNotEmpty()) kv("Capabilities", caps)
    }

    // ------------------------------------------------------------ 2 命令

    private fun commands() {
        if (!rootOk) { out("(跳过：无 root)"); return }
        for (c in listOf("screencap", "input", "dumpsys", "am", "pm", "settings", "sendevent")) {
            val p = sh.exec("which $c", 3000).trim()
            kv(c, p.ifEmpty { "!! 未找到" })
        }
        val help = sh.exec("screencap -h", 4000)
        kv("screencap 是否支持 -d", if (help.contains("-d")) "支持 ✅" else "未在帮助中看到 -d（仍会实测）")
        val inputHelp = sh.exec("input", 4000)
        kv("input 可用性", if (inputHelp.contains("Usage") || inputHelp.contains("usage")) "可用 ✅" else inputHelp.take(120))
    }

    // ------------------------------------------------------------ 3 显示

    private fun displays() {
        if (!rootOk) {
            out("(无 root，仅报告 Android 侧默认显示)")
            bestDisplayId = 0
            return
        }
        val dump = sh.exec("dumpsys display | grep -iE 'DisplayDeviceInfo|mDisplayId|Display Id|uniqueId|type=' | head -60", 8000)
        out("--- dumpsys display 摘要 ---")
        out(dump.ifEmpty { "(空)" })

        // 实测每个 displayId 能否截出有效图
        out()
        out("--- 逐个 displayId 实测 screencap ---")
        var any = false
        for (d in 0..3) {
            val f = File(cache, "probe_d$d.png")
            f.delete()
            val (ms, err) = sh.timedExec("screencap -d $d -p ${f.absolutePath}", 8000)
            val size = if (f.exists()) f.length() else 0L
            val st = analyze(f)
            if (size > 1000) any = true
            val desc = when {
                size <= 1000 -> "无有效输出 ${if (err.isNotBlank()) "｜err=${err.take(60)}" else ""}"
                st == null -> "文件存在但无法解码"
                else -> "OK  ${st[0].toInt()}x${st[1].toInt()}  方差=${fmt(st[2])}  熵=${fmt(st[3])}  ${if (isBlack(st[2], st[3])) "★黑屏/纯色" else "有内容"}"
            }
            kv("display $d", "${ms}ms  ${size}B  $desc")
            if (st != null && !isBlack(st[2], st[3]) && bestDisplayId < 0) {
                bestDisplayId = d
                frameVariance = st[2]; frameEntropy = st[3]
            }
        }
        displayCount = if (any) 4 else 0
        if (bestDisplayId < 0) {
            out("→ 所有 displayId 都截不到有内容的画面。")
            out("  若此时游戏正在前台运行，说明 screencap 通道不可用，需走 MediaProjection 兜底。")
        } else {
            out("→ 可用 displayId = $bestDisplayId（后续截图都用它）")
        }
    }

    // ------------------------------------------------------------ 4 截图

    private fun captureTest() {
        val d = if (bestDisplayId >= 0) bestDisplayId else 0
        if (!rootOk && bestDisplayId < 0) { out("(跳过：无 root)"); return }
        val f = File(cache, "probe_frame.png")
        val times = ArrayList<Long>()
        var lastSt: DoubleArray? = null
        repeat(20) { i ->
            Busy.count("截图时延测试", i + 1, 20)
            f.delete()
            val (ms, _) = sh.timedExec("screencap -d $d -p ${f.absolutePath}", 8000)
            if (f.exists() && f.length() > 1000) times.add(ms)
            if (i == 19) lastSt = analyze(f)
        }
        if (times.isEmpty()) {
            out("20 次截图全部失败 —— screencap 通道不可用")
            blackScreen = true
            return
        }
        times.sort()
        captureMedianMs = times[times.size / 2]
        captureP90Ms = times[(times.size * 9 / 10).coerceAtMost(times.size - 1)]
        kv("成功次数", "${times.size}/20")
        kv("时延 min/中位/p90/max", "${times.first()}/${captureMedianMs}/${captureP90Ms}/${times.last()} ms")
        kv("理论最高采样率", "${if (captureMedianMs > 0) 1000 / captureMedianMs else 0} Hz（仅截图，未含识别）")

        lastSt?.let {
            frameVariance = it[2]; frameEntropy = it[3]
            kv("末帧分辨率", "${it[0].toInt()} x ${it[1].toInt()}")
            kv("末帧方差", fmt(it[2]))
            kv("末帧熵", fmt(it[3]))
            blackScreen = isBlack(it[2], it[3])
            kv("黑屏判定", if (blackScreen) "★ 判定为黑屏/纯色（阈值 方差<4 或 熵<1.5）" else "正常，画面有内容 ✅")
        }
    }

    /** UI 触发：连续截图 N 秒，测持续采样率。 */
    fun burst(seconds: Int = 5): String {
        val d = if (bestDisplayId >= 0) bestDisplayId else 0
        val f = File(cache, "probe_burst.png")
        val t0 = System.currentTimeMillis()
        var n = 0; var ok = 0; var sum = 0L
        var lastSt: DoubleArray? = null
        while (System.currentTimeMillis() - t0 < seconds * 1000L) {
            f.delete()
            val (ms, _) = sh.timedExec("screencap -d $d -p ${f.absolutePath}", 8000)
            n++; sum += ms
            if (f.exists() && f.length() > 1000) { ok++; lastSt = analyze(f) }
        }
        val wall = System.currentTimeMillis() - t0
        val fps = ok * 1000.0 / max(1, wall)
        return buildString {
            append("连续截图 ${seconds}s：尝试 $n 次，成功 $ok 次\n")
            append("持续采样率 ≈ ${fmt(fps)} Hz\n")
            append("平均单次 ${if (n > 0) sum / n else 0} ms\n")
            lastSt?.let {
                append("末帧方差=${fmt(it[2])} 熵=${fmt(it[3])} → ${if (isBlack(it[2], it[3])) "黑屏/纯色" else "有内容"}\n")
            }
        }
    }

    // ------------------------------------------------------------ 5 输入

    private fun inputTest() {
        if (!rootOk) { out("(跳过：无 root)"); return }
        // keyevent 0 = KEYCODE_UNKNOWN，无副作用
        val times = ArrayList<Long>()
        var err = ""
        repeat(10) {
            val (ms, r) = sh.timedExec("input keyevent 0", 5000)
            times.add(ms)
            if (r.isNotBlank() && err.isEmpty()) err = r.take(80)
        }
        times.sort()
        kv("input keyevent 时延", "min=${times.first()}ms 中位=${times[times.size / 2]}ms max=${times.last()}ms")
        if (err.isNotEmpty()) kv("命令输出", err)
        kv("说明", "keyevent 0 是 KEYCODE_UNKNOWN，不会产生任何实际按键")
    }

    /** UI 触发：真的发一个按键，用于人工验证游戏是否响应（F-13）。 */
    fun sendKey(keycode: Int): String {
        if (!rootOk) return "无 root，无法发送按键"
        val (ms, r) = sh.timedExec("input keyevent $keycode", 6000)
        return "已发送 input keyevent $keycode （${ms}ms）\n输出: ${r.ifBlank { "(无)" }}\n请观察游戏是否响应。"
    }

    // ------------------------------------------------------------ 6 保活

    private fun keepAlive() {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val pkg = ctx.packageName
            val ignoring = pm.isIgnoringBatteryOptimizations(pkg)
            kv("电池优化豁免", if (ignoring) "已豁免 ✅" else "未豁免")
        } catch (t: Throwable) { kv("电池优化豁免", "查询失败 ${t.message}") }

        if (rootOk) {
            val wl = sh.exec("dumpsys deviceidle whitelist", 5000)
            kv("Doze 白名单含自己", if (wl.contains(ctx.packageName)) "是 ✅" else "否")
            kv("待机桶", sh.exec("am get-standby-bucket ${ctx.packageName}", 4000).trim())
        }
    }

    // ------------------------------------------------------------ 7 结论

    private fun verdict() {
        val sb = StringBuilder()
        if (!rootOk) {
            sb.append("• root 不可用 → 主通道为「无障碍 + MediaProjection」，功能可用但延迟高 3~5 倍。\n")
            sb.append("  请到红手指客户端打开 root 开关后重测。\n")
        } else {
            sb.append("• root 可用 → 通道模式：${sh.modeName}\n")
            if (sh.mode == com.altair.probe.RootShell.Mode.ONESHOT) {
                sb.append("  （本机 su 不支持交互式 shell，已自动降级；每命令多约 20ms，可接受）\n")
            }
        }
        if (bestDisplayId >= 0) {
            sb.append("• 可用 displayId = $bestDisplayId，截图正常，无黑屏。\n")
        } else if (rootOk) {
            sb.append("• ⚠ 所有 displayId 截图均无有效内容 → 确认游戏在前台后重测；仍如此则需改走 MediaProjection。\n")
        }
        if (captureMedianMs > 0) {
            val hz = 1000 / captureMedianMs
            val level = when {
                hz >= 10 -> "充裕，走位可用 5Hz 闭环"
                hz >= 4 -> "够用，走位建议降到 3Hz"
                hz >= 1.5 -> "偏紧，走位需放宽步长、降低反馈频率"
                else -> "不足，走位只能开环估计（定时长盲走）"
            }
            sb.append("• 截图中位时延 ${captureMedianMs}ms（≈${hz}Hz）→ $level\n")
        }
        if (blackScreen) sb.append("• ⚠ 黑屏判定为真 → 必须准备 MediaProjection 兜底通道。\n")
        sb.append("\n=== 后续动作 ===\n")
        sb.append("把本报告全选复制，回到对话里发给我即可。重点看：\n")
        sb.append("  ① root 探测 ② displayId ③ 截图中位时延 ④ 黑屏判定\n")
        out(sb.toString().trimEnd())
    }

    // ------------------------------------------------------------ 补充压测

    /**
     * 采集通道压测：PNG vs raw 时延对比 + raw 文件的 ROI 随机读耗时。
     *
     * 动机：P0 首轮实测 screencap -p 中位 196ms（720x1280），远慢于预期。
     * 怀疑瓶颈是 PNG 编码而非截图本身。raw 模式跳过 PNG 编码，且我们只需要
     * 小地图与血条两个小 ROI —— 可以直接 seek 读那几行字节，无需解码整图。
     * 本函数就是来验证这个假设的。
     */
    fun benchCapture(): String {
        if (!rootOk) return "无 root，无法测试"
        val d = if (bestDisplayId >= 0) bestDisplayId else 0
        val sb = StringBuilder()
        val N = 15

        val png = File(cache, "bench.png")
        val raw = File(cache, "bench.raw")

        val pngTs = ArrayList<Long>()
        repeat(N) { i ->
            Busy.count("采集压测 PNG", i + 1, N)
            png.delete()
            val (ms, _) = sh.timedExec("screencap -d $d -p ${png.absolutePath}", 10000)
            if (png.exists() && png.length() > 1000) pngTs.add(ms)
        }
        val rawTs = ArrayList<Long>()
        repeat(N) { i ->
            Busy.count("采集压测 RAW", i + 1, N)
            raw.delete()
            val (ms, _) = sh.timedExec("screencap -d $d ${raw.absolutePath}", 10000)
            if (raw.exists() && raw.length() > 1000) rawTs.add(ms)
        }

        fun med(l: List<Long>): Long = if (l.isEmpty()) -1L else l.sorted()[l.size / 2]
        val pngMed = med(pngTs)
        val rawMed = med(rawTs)
        val pngSize = if (png.exists()) png.length() else 0L
        val rawSize = if (raw.exists()) raw.length() else 0L

        sb.append("PNG  screencap -p : 成功 ${pngTs.size}/$N  中位 ${pngMed}ms  文件 $pngSize B\n")
        sb.append("RAW  screencap    : 成功 ${rawTs.size}/$N  中位 ${rawMed}ms  文件 $rawSize B\n")
        if (pngMed > 0 && rawMed > 0) {
            val pct = 100.0 * (pngMed - rawMed) / pngMed
            sb.append("→ raw 比 png ${if (rawMed < pngMed) "快" else "慢"} ${kotlin.math.abs(pngMed - rawMed)}ms（${"%.1f".format(kotlin.math.abs(pct))}%）\n")
            sb.append("→ 按 raw 推算走位闭环：${rawMed}ms截图 + ~20ms识别 + 250ms按键 = ${rawMed + 270}ms/步 ≈ ${"%.1f".format(1000.0 / (rawMed + 270))} 步/秒\n")
        }

        val hdr = parseRawHeader(raw)
        if (hdr != null) {
            sb.append("\nraw 头部: ${hdr[0]}x${hdr[1]}  format=${hdr[2]}  colorspace=${hdr[3]}  头部字节数=${hdr[4]}\n")
            val w = hdr[0]; val h = hdr[1]
            // 用归一化 ROI 换算到当前采集尺寸，这样横竖屏都对
            val rois = listOf(
                Triple("小地图", doubleArrayOf(0.0023, 0.1097, 0.1477, 0.2958), hdr),
                Triple("血条",   doubleArrayOf(0.4289, 0.8958, 0.5711, 0.9125), hdr)
            )
            for ((name, r, _) in rois) {
                val x1 = (r[0] * w).toInt(); val y1 = (r[1] * h).toInt()
                val x2 = (r[2] * w).toInt(); val y2 = (r[3] * h).toInt()
                // 重复 10 次取均值
                var best = Long.MAX_VALUE
                var cnt = 0
                repeat(10) {
                    val t0 = System.nanoTime()
                    val px = readRawRoi(raw, w, h, hdr[4], x1, y1, x2, y2)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    if (px != null) { cnt = px.size; if (ms < best) best = ms }
                }
                sb.append("ROI[$name] ${x2 - x1}x${y2 - y1} 随机读最快 ${best}ms  像素数=$cnt\n")
            }
            sb.append("→ 「raw 截图 + 只读 ROI」总时延 ≈ ${rawMed + 3}ms\n")
        } else {
            sb.append("\nraw 头部解析失败（文件 ${rawSize}B）\n")
        }
        return sb.toString()
    }

    /**
     * 延迟截图——**验证游戏画面能否截到**。
     *
     * 探针自己在前台时，截到的是探针 UI，无法证明游戏画面可截。
     * 所以：点这个按钮后探针在后台等待 [waitSec] 秒，用户切到游戏，
     * 到点后自动截图并分析。Activity 不可见不影响后台线程继续跑。
     */
    fun delayedCapture(waitSec: Int): String {
        val d = if (bestDisplayId >= 0) bestDisplayId else 0
        try { Thread.sleep(waitSec * 1000L) } catch (_: InterruptedException) {}
        val sb = StringBuilder()
        sb.append("延迟 ${waitSec}s 后截图（此刻前台应为游戏）\n")

        val raw = File(cache, "game.raw")
        raw.delete()
        val (ms, err) = sh.timedExec("screencap -d $d ${raw.absolutePath}", 12000)
        val hdr = parseRawHeader(raw)
        if (hdr == null) {
            sb.append("raw 截图失败: ${ms}ms  文件 ${if (raw.exists()) raw.length() else 0}B  ${err.take(80)}\n")
        } else {
            val w = hdr[0]; val h = hdr[1]
            sb.append("分辨率 ${w} x ${h} → ${if (w > h) "横屏（与游戏一致 ✅）" else "竖屏 ⚠ 游戏可能不在前台"}  耗时 ${ms}ms\n")
            val st = analyzeRaw(raw, hdr)
            if (st == null) {
                sb.append("raw 分析失败\n")
            } else {
                val black = isBlack(st[0], st[1])
                sb.append("方差=${fmt(st[0])}  熵=${fmt(st[1])}\n")
                sb.append(
                    if (black) "★ 判定为黑屏/纯色 → 游戏画面截不到（可能被 FLAG_SECURE 保护）\n"
                    else "有内容 ✅ 游戏画面可正常截取\n"
                )
            }
            // 顺带测一次「raw 截图 + 读小地图 ROI」的端到端耗时
            val x1 = (0.0023 * w).toInt(); val y1 = (0.1097 * h).toInt()
            val x2 = (0.1477 * w).toInt(); val y2 = (0.2958 * h).toInt()
            val t0 = System.nanoTime()
            val px = readRawRoi(raw, w, h, hdr[4], x1, y1, x2, y2)
            val roiMs = (System.nanoTime() - t0) / 1_000_000
            sb.append("小地图 ROI 读取 ${roiMs}ms  像素数=${px?.size ?: 0}\n")
        }

        // PNG 交叉验证
        val png = File(cache, "game.png")
        png.delete()
        sh.timedExec("screencap -d $d -p ${png.absolutePath}", 12000)
        val pst = analyze(png)
        if (pst != null) {
            sb.append("PNG 交叉验证: ${pst[0].toInt()}x${pst[1].toInt()} 方差=${fmt(pst[2])} 熵=${fmt(pst[3])} → ")
            sb.append(if (isBlack(pst[2], pst[3])) "★黑屏\n" else "有内容 ✅\n")
        } else {
            sb.append("PNG 交叉验证失败\n")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ 游戏中交互测试

    /**
     * 游戏中按键测试。
     *
     * ## 为什么必须延迟 + 重复
     * 探针的按钮在游戏里点不到 —— 用户按按钮时探针在前台，按键会发给探针自己。
     * 所以：用户点完按钮立刻切到游戏，[delaySec] 秒后探针在**后台**开始发送，
     * root shell 把 keyevent 注入 Android 输入系统，会送到当时的焦点窗口（游戏）。
     *
     * 之所以重复 [times] 次、每次间隔 [gapSec] 秒，是为了让用户不必卡着时间点观察。
     */
    fun gameKeyTest(
        keycode: Int, keyLabel: String,
        delaySec: Int, times: Int, gapSec: Int
    ): String {
        if (!rootOk) return "无 root，无法发送按键"
        try { Thread.sleep(delaySec * 1000L) } catch (_: InterruptedException) {}

        val sb = StringBuilder()
        sb.append("$keyLabel (code=$keycode) × $times 次，间隔 ${gapSec}s\n")
        sb.append("发送前焦点窗口: ${focusedWindow()}\n")
        val ts = ArrayList<Long>()
        for (i in 1..times) {
            val (ms, err) = sh.timedExec("input keyevent $keycode", 6000)
            ts.add(ms)
            sb.append("  第 $i 次: ${ms}ms  ${err.take(60)}\n")
            if (i < times) {
                try { Thread.sleep(gapSec * 1000L) } catch (_: InterruptedException) {}
            }
        }
        if (ts.isNotEmpty()) {
            sb.append("  命令耗时中位 ${ts.sorted()[ts.size / 2]}ms\n")
        }
        sb.append("→ 游戏里若技能/动作响应了，说明键盘通道在真机生效 ✅\n")
        sb.append("→ 完全无反应：可能是焦点不在游戏，或该键游戏未绑定\n")
        return sb.toString()
    }

    /**
     * 游戏中走位测试：向右走一段、再向左走回，重复 3 轮。
     * 用于验证「方向键连发能持续移动」这一条在真机上是否成立。
     */
    fun gameWalkTest(delaySec: Int, repeatsPerLeg: Int = 15): String {
        if (!rootOk) return "无 root，无法发送按键"
        try { Thread.sleep(delaySec * 1000L) } catch (_: InterruptedException) {}

        val sb = StringBuilder()
        sb.append("方向键连发走位测试：每段连发 $repeatsPerLeg 次，右→左 重复 3 轮\n")
        val legs = ArrayList<Long>()
        for (round in 1..3) {
            for ((label, code) in listOf("右" to 22, "左" to 21)) {
                val t0 = System.nanoTime()
                var cmdSum = 0L
                var okCnt = 0
                repeat(repeatsPerLeg) {
                    val (ms, _) = sh.timedExec("input keyevent $code", 4000)
                    cmdSum += ms
                    okCnt++
                }
                val dt = (System.nanoTime() - t0) / 1_000_000
                legs.add(dt)
                sb.append("  第 $round 轮 $label: 连发 $okCnt 次，墙钟 ${dt}ms（命令累计 ${cmdSum}ms）\n")
                try { Thread.sleep(800) } catch (_: InterruptedException) {}
            }
            try { Thread.sleep(600) } catch (_: InterruptedException) {}
        }
        if (legs.isNotEmpty()) {
            val avg = legs.average().toLong()
            sb.append("  平均每段 ${avg}ms → 单次按键 ≈ ${avg / repeatsPerLeg}ms\n")
        }
        sb.append("→ 角色若来回移动了，方向键连发方案成立 ✅\n")
        sb.append("→ 只动一下就停：需要改用 keyHold 或提高连发频率\n")
        return sb.toString()
    }

    /**
     * 悬浮窗用的快速截图：一次报告分辨率/方向/方差/熵/黑屏判定 + 当前焦点窗口。
     * 用来在游戏画面上随时确认「画面截得到吗」「现在谁在前台」。
     */
    fun quickCapture(): String {
        if (!ensureShell()) return "无 root"
        val d = if (bestDisplayId >= 0) bestDisplayId else 0
        val f = File(cache, "quick.raw")
        f.delete()
        val (ms, err) = sh.timedExec("screencap -d $d ${f.absolutePath}", 12000)
        val sb = StringBuilder()
        sb.append("焦点: ").append(focusedWindow()).append('\n')
        val hdr = parseRawHeader(f)
        if (hdr == null) {
            sb.append("raw 截图失败 ${ms}ms  ${err.take(60)}")
            return sb.toString()
        }
        val w = hdr[0]; val h = hdr[1]
        sb.append("截图 ${w}x${h} ${if (w > h) "横屏（与游戏一致）" else "竖屏 ⚠ 游戏可能不在前台"}  ${ms}ms\n")
        analyzeRaw(f, hdr)?.let {
            sb.append("方差=${fmt(it[0])} 熵=${fmt(it[1])} → ")
            sb.append(if (isBlack(it[0], it[1])) "★ 黑屏/纯色 ⚠" else "有内容 ✅")
        }
        return sb.toString()
    }

    /**
     * 一次画面统计：返回 [方差, 熵, 平均亮度(0-255)]，失败返回 null。
     *
     * 给「等菜单弹出」「等过图黑屏过去」这类流程判定用。
     * 之所以不解析 quickCapture 的文本：那是给人看的，格式一变流程就悄悄失效。
     */
    fun screenStats(): DoubleArray? {
        if (!ensureShell()) return null
        val d = if (bestDisplayId >= 0) bestDisplayId else 0
        val f = File(cache, "stats.raw")
        f.delete()
        sh.timedExec("screencap -d $d ${f.absolutePath}", 12000)
        val hdr = parseRawHeader(f) ?: return null
        return analyzeRaw(f, hdr)
    }

    /** 画面是否黑屏/纯色（过图加载中）。取不到画面时返回 null，调用方自己决定怎么办。 */
    fun isScreenBlack(): Boolean? = screenStats()?.let { isBlack(it[0], it[1]) }

    /** 整屏平均亮度（0-255）。菜单这类半透明遮罩会让它明显下降。 */
    fun screenBrightness(): Double? = screenStats()?.getOrNull(2)

    // ------------------------------------------------------------ 按键扫描

    /**
     * 按键扫描。
     *
     * 背景：实机已证明 **方向键生效** → `input keyevent` 能送达游戏，游戏也处理 KeyEvent。
     * 那「数字键1无效」就只有一种解释：**游戏没把技能绑在 KEYCODE_1 上**。
     *
     * 既然通道是通的，就直接把常见动作键挨个试一遍，找出真正能触发技能的那个。
     * 每个键之间有间隔，日志会先打印"即将发送 XXX"，方便你盯着游戏看是哪一个生效。
     */
    fun keyScan(delaySec: Int, gapMs: Int = 2200): String {
        if (!ensureShell()) return "无 root"
        try { Thread.sleep(delaySec * 1000L) } catch (_: InterruptedException) {}

        // 候选键：数字 1-8、字母常用键、以及各种修饰/动作键
        val candidates = listOf(
            "数字1" to 8, "数字2" to 9, "数字3" to 10, "数字4" to 11,
            "数字5" to 12, "数字6" to 13, "数字7" to 14, "数字8" to 15,
            "Q" to 45, "W" to 51, "E" to 33, "R" to 46,
            "A" to 29, "S" to 47, "D" to 32, "F" to 34,
            "空格" to 62, "回车" to 66, "Tab" to 61,
            "左Shift" to 59, "左Ctrl" to 113, "左Alt" to 57,
            "Z" to 54, "X" to 52, "C" to 31, "V" to 50, "B" to 30
        )

        val sb = StringBuilder()
        sb.append("按键扫描：共 ${candidates.size} 个候选，每个间隔 ${gapMs}ms\n")
        sb.append("请盯着游戏，记下**哪一个**让技能放出来了。\n\n")
        sb.append("焦点: ").append(focusedWindow()).append("\n\n")

        candidates.forEachIndexed { idx, (name, code) ->
            Busy.count("键扫描", idx + 1, candidates.size)
            val (ms, _) = sh.timedExec("input keyevent $code", 6000)
            sb.append("  发 ${name} (code=$code)  ${ms}ms\n")
            try { Thread.sleep(gapMs.toLong()) } catch (_: InterruptedException) {}
        }
        sb.append("\n→ 把「哪一个生效了」告诉我，我把它写进配置。\n")
        sb.append("→ 若全部无效，说明技能确实只能靠触摸，那就继续走采点+触摸方案。\n")
        return sb.toString()
    }

    // ------------------------------------------------------------ 触摸点击（键盘走不通时的方案）

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

    // ------------------------------------------------------------ 前台应用检测

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

    /** 前台是否就是目标游戏。 */
    fun isTargetForeground(target: String): Boolean =
        target.isNotBlank() && foregroundPackage() == target

    // ------------------------------------------------------------ 按键通道诊断

    /**
     * 当前有输入焦点的窗口。
     *
     * 这是判断「按键到底发给了谁」的唯一可靠依据。
     * 如果发键时焦点不在游戏上，那"按键无效"就跟游戏绑不绑定键无关了。
     */
    fun focusedWindow(): String {
        if (!rootOk) return "(无 root)"
        val a = sh.exec("dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -4", 6000)
        val b = sh.exec("dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | head -2", 6000)
        val t = (a + "\n" + b).lines().map { it.trim() }.filter { it.isNotEmpty() }
            .distinct().joinToString(" | ")
        return t.ifEmpty { "(取不到焦点信息)" }
    }

    /**
     * 按键通道诊断：区分「注入没生效」与「游戏不认这些键」。
     *
     * 设计要点：
     *   - 顺序很重要：先发游戏用的数字键（此时游戏应在前台），
     *     再发 BACK（游戏通常会弹退出确认），
     *     **最后**才发 HOME（会离开游戏，所以放最后）。
     *   - 每一步都记录焦点窗口，这样即使结果异常也能定位原因。
     */
    fun keyDiagnostics(delaySec: Int): String {
        if (!rootOk) return "无 root，无法诊断"
        try { Thread.sleep(delaySec * 1000L) } catch (_: InterruptedException) {}

        val sb = StringBuilder()
        sb.append("按键通道诊断\n")
        sb.append("起始焦点: ${focusedWindow()}\n\n")

        // 第一步：数字键 1（游戏该响应的键）
        sb.append("【1】数字键1 (KEYCODE_1=8) —— 期望：游戏放出技能\n")
        repeat(3) { i ->
            val (ms, err) = sh.timedExec("input keyevent 8", 6000)
            sb.append("    第 ${i + 1} 次: ${ms}ms  ${err.take(40)}\n")
            try { Thread.sleep(2500) } catch (_: InterruptedException) {}
        }

        // 第二步：BACK —— 游戏通常会弹「确认退出」
        sb.append("\n【2】返回键 (KEYCODE_BACK=4) —— 期望：游戏弹退出确认\n")
        val (bms, berr) = sh.timedExec("input keyevent 4", 6000)
        sb.append("    ${bms}ms  ${berr.take(40)}\n")
        try { Thread.sleep(2500) } catch (_: InterruptedException) {}

        // 第三步：HOME —— 系统级，任何环境都该响应。放最后，因为它会离开游戏
        sb.append("\n【3】Home 键 (KEYCODE_HOME=3) —— 期望：云手机回到桌面\n")
        val (hms, herr) = sh.timedExec("input keyevent 3", 6000)
        sb.append("    ${hms}ms  ${herr.take(40)}\n")
        try { Thread.sleep(2000) } catch (_: InterruptedException) {}
        sb.append("发送后焦点: ${focusedWindow()}\n")

        sb.append(
            """
            |
            |=== 怎么读这个结果 ===
            |  · Home 生效（回桌面了）  → 注入通道正常 ✅
            |      那「数字键无效」= 游戏不绑定这些键
            |      → 必须改用触摸点击（需要标定技能键坐标）
            |  · Home 也没反应          → input 注入被环境限制
            |      → 改用 sendevent 底层注入，或直接走触摸
            |  · Home 生效且 BACK 也生效 → 通道完全正常，纯粹是游戏键位问题
            """.trimMargin()
        )
        return sb.toString()
    }

    /**
     * 触摸通道测试：在「右侧中部技能键排」的估计位置上依次点击。
     * 用来验证触摸可用性，同时顺便验证我此前对技能键位置的估计对不对。
     */
    fun tapTest(delaySec: Int): String {
        if (!rootOk) return "无 root"
        try { Thread.sleep(delaySec * 1000L) } catch (_: InterruptedException) {}

        // 先截一帧拿到**当前真实方向**的尺寸（游戏横屏时是 1280x720）
        val ref = File(cache, "tapref.raw")
        ref.delete()
        sh.timedExec("screencap -d ${if (bestDisplayId >= 0) bestDisplayId else 0} ${ref.absolutePath}", 10000)
        val hdr = parseRawHeader(ref)
        val w = hdr?.get(0) ?: 1280
        val h = hdr?.get(1) ?: 720

        val sb = StringBuilder()
        sb.append("触摸测试\n")
        sb.append("当前采集尺寸 ${w} x ${h} → ${if (w > h) "横屏（与游戏一致）" else "竖屏 ⚠ 游戏可能不在前台"}\n")
        sb.append("焦点: ${focusedWindow()}\n\n")

        // 用 v0.13 实机采点得到的**真实**技能键坐标（此前推算的位置偏左了）
        val pts = listOf(
            0.7416 to 0.5673,
            0.8041 to 0.5756,
            0.8649 to 0.5673,
            0.9180 to 0.5728
        )
        for ((i, p) in pts.withIndex()) {
            val px = (p.first * w).toInt()
            val py = (p.second * h).toInt()
            val (ms, err) = sh.timedExec("input swipe $px $py $px $py 90", 6000)
            sb.append("  点击 ${i + 1}: (${px}, ${py})  ${ms}ms  ${err.take(30)}\n")
            try { Thread.sleep(2500) } catch (_: InterruptedException) {}
        }
        sb.append(
            """
            |
            |→ 有技能被放出来：触摸通道可用，且技能键位置估计正确 ✅
            |→ 完全没反应：要么位置不对，要么触摸也受限
            |   （位置可用 tools/calibrate 标定工具重新框，我给你换算成坐标）
            """.trimMargin()
        )
        return sb.toString()
    }

    // ------------------------------------------------------------ raw 截图支持

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

    /**
     * 读一块区域的 RGB（打包成 0xRRGGBB）。row-major RGBA_8888。
     * 血条检测要看颜色，光有亮度不够，所以单独开一个。
     */
    private fun readRawRoiRgb(
        f: File, w: Int, h: Int, headerBytes: Int,
        x1: Int, y1: Int, x2: Int, y2: Int
    ): IntArray? {
        return try {
            val rw = (x2 - x1).coerceAtMost(w).coerceAtLeast(0)
            val rh = (y2 - y1).coerceAtMost(h).coerceAtLeast(0)
            if (rw <= 0 || rh <= 0) return null
            RandomAccessFile(f, "r").use { raf ->
                val rowBytes = w * 4
                val buf = ByteArray(rowBytes)
                val out = IntArray(rw * rh)
                for (y in 0 until rh) {
                    raf.seek(headerBytes.toLong() + (y1 + y).toLong() * rowBytes)
                    raf.readFully(buf)
                    val base = y * rw
                    for (x in 0 until rw) {
                        val o = (x1 + x) * 4
                        val r = buf[o].toInt() and 0xFF
                        val g = buf[o + 1].toInt() and 0xFF
                        val b = buf[o + 2].toInt() and 0xFF
                        out[base + x] = (r shl 16) or (g shl 8) or b
                    }
                }
                out
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 血条红像素判据（实机实测签名：主体 R195..249/G0/B0，外圈粉描边 R255/G96/B96）。 */
    private fun isHpBarRed(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return r >= 180 && g <= 110 && b <= 110 && (r - g) >= 90
    }

    /**
     * 给视觉检测用的一次性原始画面（RGB 打包）。返回 Triple(w, h, rgb)，失败 null。
     *
     * 注意：整屏 RGB 是 w*h 个 Int（1280x720 ≈ 3.7MB）。只在**手动触发/低频**检测里用，
     * 不要放进每帧循环。
     */
    fun captureRawForVision(): Triple<Int, Int, IntArray>? {
        if (!ensureShell()) return null
        val d = if (bestDisplayId >= 0) bestDisplayId else 0
        val f = File(cache, "vision.raw")
        f.delete()
        sh.timedExec("screencap -d $d ${f.absolutePath}", 12000)
        val hdr = parseRawHeader(f) ?: return null
        val w = hdr[0]; val h = hdr[1]; val hb = hdr[4]
        val rgb = readRawRoiRgb(f, w, h, hb, 0, 0, w, h) ?: return null
        return Triple(w, h, rgb)
    }

    /**
     * 找**角色头顶血条**，返回归一化 [x, y, w, h]（左上角 + 宽高）。找不到返回 null。
     *
     * 这是设备端唯一可靠的「角色现在在哪」信号 —— 闭环走位与 ROI 动态显示都靠它。
     * （试过小地图角色点：太小、和场景杂点混在一起，实测分辨不出来。）
     *
     * 签名来自实机实测（详细设计 F-17）：主体纯红渐变 R195..249 / G0 / B0，
     * 外圈浅粉描边 R255 / G96 / B96。扫描只取角色头顶可能出现的那条横带
     * （默认 y 50%~60%），逐行找「红像素连续段」，再验竖向厚度 4~10px、宽 20~120px。
     * 宽度下限放到 20 是因为角色贴屏幕边缘时血条会被裁掉一截。
     */
    fun findHeadHpBarBox(bandLo: Double = 0.50, bandHi: Double = 0.60): FloatArray? =
        // ★ 必须藏起 ROI 再抓图：否则 ROI 自己画的红框会被当成血条，检测位置越走越偏
        //   （用户反馈"第一次准、之后往右上漂"）。见 OverlayService.withRoiHidden。
        OverlayService.withRoiHidden { findHeadHpBarBoxRaw(bandLo, bandHi) }

    private fun findHeadHpBarBoxRaw(bandLo: Double, bandHi: Double): FloatArray? {
        if (!ensureShell()) return null
        val d = if (bestDisplayId >= 0) bestDisplayId else 0
        val f = File(cache, "hpbar.raw")
        f.delete()
        sh.timedExec("screencap -d $d ${f.absolutePath}", 12000)
        val hdr = parseRawHeader(f) ?: return null
        val w = hdr[0]; val h = hdr[1]; val hb = hdr[4]
        val y0 = (h * bandLo).toInt().coerceIn(0, h - 1)
        val y1 = (h * bandHi).toInt().coerceIn(y0 + 1, h)
        val band = readRawRoiRgb(f, w, h, hb, 0, y0, w, y1) ?: return null
        val bh = y1 - y0

        var bestScore = 0
        var bx = -1; var by = -1; var bwd = 0; var bht = 0
        for (ry in 0 until bh) {
            var x = 0
            while (x < w) {
                if (!isHpBarRed(band[ry * w + x])) { x++; continue }
                var xe = x
                while (xe + 1 < w && isHpBarRed(band[ry * w + xe + 1])) xe++
                val runW = xe - x + 1
                if (runW in 20..120) {
                    var thick = 0
                    var yy = ry
                    while (yy < bh && thick < 12) {
                        var ok = 0
                        for (xx in x..xe) if (isHpBarRed(band[yy * w + xx])) ok++
                        if (ok.toDouble() / runW > 0.6) thick++ else break
                        yy++
                    }
                    if (thick in 4..10) {
                        val score = runW * thick
                        if (score > bestScore) {
                            bestScore = score
                            bx = x; by = ry; bwd = runW; bht = thick
                        }
                    }
                }
                x = xe + 1
            }
        }
        if (bx < 0) return null
        return floatArrayOf(
            bx.toFloat() / w,
            (y0 + by).toFloat() / h,
            bwd.toFloat() / w,
            bht.toFloat() / h,
        )
    }

    /** 头顶血条中心 x（归一化）。ROI 对齐与闭环走位用。 */
    fun findHeadHpBarX(bandLo: Double = 0.50, bandHi: Double = 0.60): Double? =
        findHeadHpBarBox(bandLo, bandHi)?.let { (it[0] + it[2] / 2f).toDouble() }

    /** 从 raw 文件里只读一个矩形区域的亮度值。row-major RGBA_8888。 */
    private fun readRawRoi(
        f: File, w: Int, h: Int, headerBytes: Int,
        x1: Int, y1: Int, x2: Int, y2: Int
    ): IntArray? {
        return try {
            val rw = (x2 - x1).coerceAtMost(w).coerceAtLeast(0)
            val rh = (y2 - y1).coerceAtMost(h).coerceAtLeast(0)
            if (rw <= 0 || rh <= 0) return null
            RandomAccessFile(f, "r").use { raf ->
                val rowBytes = w * 4
                val buf = ByteArray(rowBytes)
                val lum = IntArray(rw * rh)
                for (y in 0 until rh) {
                    raf.seek(headerBytes.toLong() + (y1 + y).toLong() * rowBytes)
                    raf.readFully(buf)
                    for (x in 0 until rw) {
                        val o = (x1 + x) * 4
                        val r = buf[o].toInt() and 0xFF
                        val g = buf[o + 1].toInt() and 0xFF
                        val b = buf[o + 2].toInt() and 0xFF
                        lum[y * rw + x] = (299 * r + 587 * g + 114 * b) / 1000
                    }
                }
                lum
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 抽样分析 raw 的方差与熵。 */
    private fun analyzeRaw(f: File, hdr: IntArray): DoubleArray? {
        val w = hdr[0]; val h = hdr[1]; val hb = hdr[4]
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val step = max(1, min(w, h) / 200)
                val rowBytes = w * 4
                val buf = ByteArray(rowBytes)
                var n = 0; var s1 = 0.0; var s2 = 0.0
                val hist = IntArray(256)
                var y = 0
                while (y < h) {
                    raf.seek(hb.toLong() + y.toLong() * rowBytes)
                    raf.readFully(buf)
                    var x = 0
                    while (x < w) {
                        val o = x * 4
                        val r = buf[o].toInt() and 0xFF
                        val g = buf[o + 1].toInt() and 0xFF
                        val b = buf[o + 2].toInt() and 0xFF
                        val lum = (299 * r + 587 * g + 114 * b) / 1000
                        s1 += lum; s2 += lum.toDouble() * lum; hist[lum]++; n++
                        x += step
                    }
                    y += step
                }
                if (n == 0) return null
                val mean = s1 / n
                var ent = 0.0
                for (c in hist) if (c > 0) { val p = c.toDouble() / n; ent -= p * ln(p) }
                // 第三个值是平均亮度（0-255）：判断「菜单弹出导致整屏变暗」
                // 「过图黑屏」这类场景要看整屏亮度，光有方差/熵不够。
                doubleArrayOf(s2 / n - mean * mean, ent, mean)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 释放常驻 root shell。 */
    fun close() {
        try { sh.close() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------ 图像分析

    /** 返回 [width, height, variance, entropy]；无法解码返回 null。 */
    private fun analyze(f: File): DoubleArray? {
        if (!f.exists() || f.length() < 1000) return null
        val bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null } ?: return null
        return try {
            val w = bmp.width; val h = bmp.height
            val step = max(1, min(w, h) / 200)
            var n = 0; var s1 = 0.0; var s2 = 0.0
            val hist = IntArray(256)
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val p = bmp.getPixel(x, y)
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val lum = (299 * r + 587 * g + 114 * b) / 1000
                    s1 += lum; s2 += lum.toDouble() * lum; hist[lum]++; n++
                    x += step
                }
                y += step
            }
            if (n == 0) return null
            val mean = s1 / n
            val varr = s2 / n - mean * mean
            var ent = 0.0
            for (c in hist) if (c > 0) { val p = c.toDouble() / n; ent -= p * ln(p) }
            doubleArrayOf(w.toDouble(), h.toDouble(), varr, ent)
        } catch (_: Throwable) {
            null
        } finally {
            try { bmp.recycle() } catch (_: Throwable) {}
        }
    }

    private fun isBlack(varr: Double, ent: Double) = varr < 4.0 || ent < 1.5

    private fun fmt(d: Double) = String.format("%.2f", d)
}
