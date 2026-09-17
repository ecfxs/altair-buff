package com.altair.probe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import java.io.File

/**
 * P0 探测 APK 的界面。
 *
 * 设计原则：云手机上操作不便，所以
 *   - 按钮少而明确
 *   - 输出区可用、等宽、可全选
 *   - 提供「一键复制」，配合红手指的剪贴板同步把报告带回 PC
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var body: LinearLayout
    private lateinit var urlField: EditText
    private lateinit var autoChk: CheckBox
    private lateinit var forceChk: CheckBox
    private val probe by lazy { Probe(this) { line -> runOnUiThread { appendLine(line) } } }
    private val updater by lazy {
        Updater(this, probe.shell) { line -> runOnUiThread { appendLine(line) } }
    }
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        appendLine("P0 探测工具 v" + updater.currentVersionName() + " · 冒险岛世界/阿尔泰")
        appendLine("用途：实测 root 真伪、screencap 是否黑屏、真实 displayId、截图与按键时延。")
        appendLine()
        appendLine("推荐顺序：")
        appendLine("  ① 开始探测              — 基础环境与通道探测")
        appendLine("  ② 连续截图 5s           — 持续采样率")
        appendLine("  ③ 采集压测              — PNG vs RAW 时延对比（决定走位能否闭环）")
        appendLine("  ④ 游戏中截图(10s)       — 点完立刻切到游戏，验证游戏画面可截")
        appendLine("  ⑤ 游戏中按键测试           — 验证游戏是否响应数字键")
        appendLine("  ⑥ 按键通道诊断             — 区分「注入失败」还是「游戏不认键」★★★")
        appendLine("  ⑦ 触摸测试                 — 键盘走不通时，验证触摸点击方案")
        appendLine()
        appendLine("注意：探测会反复执行 screencap，游戏会短暂卡顿，属正常。")
        appendLine()
        appendLine("准备就绪。")
        startupUpdateCheck()
    }

    // ------------------------------------------------------------ UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101317"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val title = TextView(this).apply {
            text = "P0 探测 · 红手指云手机"
            setTextColor(Color.parseColor("#E6EDF5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        status = TextView(this).apply {
            text = "空闲"
            setTextColor(Color.parseColor("#8FA3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(2), 0, dp(8))
        }
        root.addView(status)

        root.addView(buttonRow(
            "① 开始探测" to { runProbe() },
            "② 连续截图 5s" to { runBurst() },
            "③ 采集压测" to { runBench() }
        ))
        root.addView(buttonRow(
            "④ 游戏中截图(10s)" to { runDelayed(10) },
            "⑤ 游戏中按键测试" to { runGameKeys() },
            "⑥ 按键通道诊断" to { runKeyDiag() }
        ))
        root.addView(buttonRow(
            "⑦ 触摸测试(10s)" to { runTapTest() },
            "复制报告" to { copyReport() },
            "保存到文件" to { saveReport() }
        ))
        root.addView(buttonRow(
            "分享/导出" to { shareReport() },
            "清空" to { body.removeAllViews(); appendLine("已清空。") }
        ))

        // ---------------- 自更新区 ----------------
        urlField = EditText(this).apply {
            hint = "更新源 APK 直链（或本地 APK 路径）"
            setText(updater.savedUrl())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#C9D4E0"))
            setHintTextColor(Color.parseColor("#5A6675"))
            setBackgroundColor(Color.parseColor("#161A20"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        root.addView(urlField)

        val optRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        autoChk = CheckBox(this).apply {
            text = "启动自动检查更新"
            isChecked = updater.autoCheck()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#8FA3B8"))
        }
        forceChk = CheckBox(this).apply {
            text = "强制重装"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#8FA3B8"))
        }
        optRow.addView(autoChk)
        optRow.addView(forceChk)
        root.addView(optRow)

        root.addView(buttonRow(
            "自更新" to { runSelfUpdate() },
            "安装本地APK" to { runLocalInstall() },
            "更新日志" to { runShowUpdateLog() }
        ))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0E12"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scroll.addView(body)
        root.addView(scroll)

        setContentView(root)
    }

    private fun buttonRow(vararg btns: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        btns.forEach { (label, action) ->
            row.addView(Button(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
                setOnClickListener { if (!busy) action() else toast("正在执行，请稍候…") }
            })
        }
        return row
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ 输出

    private val buffer = StringBuilder()

    private fun appendLine(s: String = "") {
        buffer.append(s).append('\n')
        val tv = TextView(this).apply {
            text = s
            setTextColor(
                when {
                    s.startsWith("==") -> Color.parseColor("#7FD18B")
                    s.contains("★") || s.contains("⚠") || s.contains("!!") -> Color.parseColor("#FFB454")
                    s.contains("✅") -> Color.parseColor("#7FD18B")
                    else -> Color.parseColor("#C9D4E0")
                }
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        body.addView(tv)
        (body.parent as? ScrollView)?.post {
            (body.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun setBusy(b: Boolean, label: String = "") {
        busy = b
        status.text = if (b) "执行中：$label …" else "空闲"
        status.setTextColor(if (b) Color.parseColor("#FFB454") else Color.parseColor("#8FA3B8"))
    }

    // ------------------------------------------------------------ 动作

    private fun runProbe() = background("完整探测") {
        val report = probe.run()
        runOnUiThread { appendLine(); appendLine("=== 探测完成，可用「复制报告」把结果带回 PC ===") }
        lastReport = report
    }

    private fun runBurst() = background("连续截图") {
        val r = probe.burst(5)
        runOnUiThread { appendLine(); r.split('\n').forEach { appendLine(it) } }
    }

    private fun runBench() = background("采集压测") {
        val r = probe.benchCapture()
        runOnUiThread {
            appendLine()
            appendLine("--- 采集通道压测：PNG vs RAW ---")
            r.split('\n').forEach { appendLine(it) }
        }
    }

    // ------------------------------------------------------------ 自更新

    private fun runSelfUpdate() {
        val url = urlField.text.toString().trim()
        if (url.isEmpty()) { toast("请先填更新源 URL"); return }
        updater.saveUrl(url)
        updater.setAutoCheck(autoChk.isChecked)
        appendLine()
        appendLine("--- 自更新 ---")
        background("自更新") {
            val r = updater.updateFromUrl(url, forceChk.isChecked)
            runOnUiThread { r.split('\n').forEach { appendLine(it) } }
        }
    }

    private fun runLocalInstall() {
        val path = urlField.text.toString().trim()
        if (path.isEmpty()) { toast("请先填本地 APK 路径"); return }
        appendLine()
        appendLine("--- 安装本地 APK ---")
        background("安装本地APK") {
            val r = updater.installLocal(path, forceChk.isChecked)
            runOnUiThread { r.split('\n').forEach { appendLine(it) } }
        }
    }

    private fun runShowUpdateLog() {
        background("读取更新日志") {
            val r = updater.readUpdateLog()
            runOnUiThread {
                appendLine()
                appendLine("--- /data/local/tmp/altair_update.log ---")
                r.split('\n').forEach { appendLine(it) }
            }
        }
    }

    /** 启动后在后台做一次自更新检查（不阻塞 UI，也不打断正在做的事）。 */
    private fun startupUpdateCheck() {
        val url = updater.savedUrl()
        val auto = updater.autoCheck()
        Thread {
            // 先把上次的更新结果读出来
            try {
                if (updater.hasUpdateLog()) {
                    val lg = updater.readUpdateLog()
                    runOnUiThread {
                        appendLine()
                        appendLine("--- 上次自更新结果（/data/local/tmp/altair_update.log）---")
                        lg.split('\n').takeLast(12).forEach { appendLine(it) }
                    }
                }
            } catch (_: Throwable) {}
            if (url.isEmpty() || !auto) return@Thread
            try { Thread.sleep(4000) } catch (_: InterruptedException) {}
            try {
                val r = updater.updateFromUrl(url, false)
                runOnUiThread {
                    appendLine()
                    appendLine("--- 启动自动检查更新 ---")
                    r.split('\n').forEach { appendLine(it) }
                }
            } catch (t: Throwable) {
                runOnUiThread { appendLine("自动检查更新失败: ${t.message}") }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 游戏中按键测试。
     * 关键：探针的按钮在游戏里点不到，所以必须「延迟 + 重复发送」。
     */
    private fun runGameKeys() {
        val delay = 10
        appendLine()
        appendLine(">>> 已启动：$delay 秒后自动发送【数字键1】共 5 次，间隔 3 秒 <<<")
        appendLine(">>> 请立刻切到游戏并保持前台，观察技能是否释放 <<<")
        background("游戏中按键") {
            val r = probe.gameKeyTest(8, "数字键1", delay, times = 5, gapSec = 3)
            runOnUiThread {
                appendLine()
                appendLine("--- 游戏中按键测试结果 ---")
                r.split('\n').forEach { appendLine(it) }
                toast("按键测试结束")
            }
        }
    }

    /**
     * 按键通道诊断 —— 用来区分「注入没生效」与「游戏不认这些键」。
     * 点完立刻切到游戏。最后会发 Home 键，云手机会回桌面，属正常。
     */
    private fun runKeyDiag() {
        val delay = 10
        appendLine()
        appendLine(">>> 已启动：$delay 秒后依次发送 数字键1×3 → 返回键 → Home键 <<<")
        appendLine(">>> 请立刻切到游戏。最后会回桌面（Home 键），属正常 <<<")
        background("按键通道诊断") {
            val r = probe.keyDiagnostics(delay)
            runOnUiThread {
                appendLine()
                appendLine("--- 按键通道诊断结果 ---")
                r.split('\n').forEach { appendLine(it) }
                toast("诊断结束")
            }
        }
    }

    /** 触摸通道测试：在估计的技能键排上依次点击 4 个位置。 */
    private fun runTapTest() {
        val delay = 10
        appendLine()
        appendLine(">>> 已启动：$delay 秒后依次点击右侧技能键排的 4 个估计位置 <<<")
        appendLine(">>> 请立刻切到游戏，观察是否有技能被放出来 <<<")
        background("触摸测试") {
            val r = probe.tapTest(delay)
            runOnUiThread {
                appendLine()
                appendLine("--- 触摸测试结果 ---")
                r.split('\n').forEach { appendLine(it) }
                toast("触摸测试结束")
            }
        }
    }

    /** 游戏中走位测试：延迟后自动连发方向键，右→左 重复 3 轮。 */
    private fun runGameWalk() {
        val delay = 10
        appendLine()
        appendLine(">>> 已启动：$delay 秒后自动连发方向键（右15次 → 左15次）× 3 轮 <<<")
        appendLine(">>> 请立刻切到游戏，观察角色是否来回移动 <<<")
        background("游戏中走位") {
            val r = probe.gameWalkTest(delay)
            runOnUiThread {
                appendLine()
                appendLine("--- 游戏中走位测试结果 ---")
                r.split('\n').forEach { appendLine(it) }
                toast("走位测试结束")
            }
        }
    }

    /**
     * 延迟截图：点完立刻切到游戏，10 秒后自动截图并分析。
     * 这是唯一能证明「游戏画面可截」的测试 —— 探针在前台时截到的是探针自己。
     * Activity 不在前台不影响后台线程继续跑。
     */
    private fun runDelayed(sec: Int) {
        appendLine()
        appendLine(">>> 请在 $sec 秒内切换到游戏并保持前台 <<<")
        appendLine("（到点后自动截图，然后切回本应用看结果）")
        background("游戏中截图") {
            val r = probe.delayedCapture(sec)
            runOnUiThread {
                appendLine()
                appendLine("--- 延迟截图结果（游戏画面可截性验证）---")
                r.split('\n').forEach { appendLine(it) }
                toast("延迟截图完成")
            }
        }
    }

    private fun sendKey(code: Int, label: String) {
        background("发送 $label") {
            val r = probe.sendKey(code)
            runOnUiThread { appendLine(); appendLine("--- 测试按键：$label (code=$code) ---"); appendLine(r) }
        }
    }

    private fun background(label: String, block: () -> Unit) {
        setBusy(true, label)
        Thread {
            try {
                block()
            } catch (t: Throwable) {
                runOnUiThread { appendLine("!! 出错: ${t.javaClass.simpleName}: ${t.message}") }
            } finally {
                runOnUiThread { setBusy(false) }
            }
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------ 导出

    private var lastReport: String? = null

    private fun currentReport(): String =
        lastReport ?: buffer.toString().ifBlank { "（还没有内容）" }

    private fun copyReport() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("probe", currentReport()))
        toast("已复制到剪贴板（${currentReport().length} 字）")
    }

    private fun shareReport() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "P0 探测报告")
            putExtra(Intent.EXTRA_TEXT, currentReport())
        }
        startActivity(Intent.createChooser(i, "分享探测报告"))
    }

    private fun saveReport() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = File(dir, "probe_report.txt")
            f.writeText(currentReport())
            appendLine()
            appendLine("已保存: ${f.absolutePath}")
            appendLine("（也可用文件管理器到 Android/data/${packageName}/files/ 取）")
            toast("已保存")
        } catch (t: Throwable) {
            toast("保存失败: ${t.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { probe.close() } catch (_: Throwable) {}
    }
}
