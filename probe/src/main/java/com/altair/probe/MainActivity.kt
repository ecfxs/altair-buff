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
    private lateinit var mgmtField: EditText
    private lateinit var mgmtChk: CheckBox
    private lateinit var mgmtToken: EditText
    private val mgmt by lazy { Management(this, ShellCore.root) }
    // 与悬浮窗共用同一套核心（同一个 su 进程、同一份日志）
    private val probe get() = ShellCore.probe
    private val updater get() = ShellCore.updater

    /** LogBus 的监听器：把日志渲染到界面。 */
    private val logListener: (String) -> Unit = { line -> runOnUiThread { renderLine(line) } }
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShellCore.init(this)
        buildUi()
        LogBus.add(logListener)
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
        appendLine("集控：填服务器地址 + 勾选启用，设备会定时上报状态并拉取配置")
        appendLine("自更新：点「自更新(多源)」会自动在 代理/GitHub/jsDelivr 之间切换")
        appendLine("        （实测 GitHub 直连在国内经常不通，故默认走代理）")
        appendLine()
        appendLine("注意：探测会反复执行 screencap，游戏会短暂卡顿，属正常。")
        appendLine()
        appendLine("准备就绪。")
        startupUpdateCheck()
        maybeStartMgmt()
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
            "★ 申请Root权限" to { runRequestRoot() },
            "自更新(多源)" to { runSelfUpdate() }
        ))
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
            "启动悬浮窗 ★" to { startOverlay() },
            "停止悬浮窗" to { stopOverlay() }
        ))
        root.addView(buttonRow(
            "复制报告" to { copyReport() },
            "保存到文件" to { saveReport() }
        ))
        root.addView(buttonRow(
            "分享/导出" to { shareReport() },
            "清空" to { body.removeAllViews(); LogBus.clear(); appendLine("已清空。") }
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
            text = "启动时自动检查更新（默认关）"
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
            "按URL更新" to { runUpdateByUrl() },
            "安装本地APK" to { runLocalInstall() },
            "更新日志" to { runShowUpdateLog() }
        ))

        // ---------------- 集控（多设备统一管理） ----------------
        mgmtField = EditText(this).apply {
            hint = "集控服务器地址，如 http://1.2.3.4:8080"
            setText(mgmt.server)
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
        root.addView(mgmtField)

        mgmtToken = EditText(this).apply {
            hint = "集控 Token（在集控面板页面上可复制）"
            setText(mgmt.token)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#C9D4E0"))
            setHintTextColor(Color.parseColor("#5A6675"))
            setBackgroundColor(Color.parseColor("#161A20"))
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        root.addView(mgmtToken)

        mgmtChk = CheckBox(this).apply {
            text = "启用集控定期上报（设备主动上报+拉配置，云手机在NAT后只能这么做）"
            isChecked = mgmt.enabled
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor("#8FA3B8"))
        }
        root.addView(mgmtChk)

        root.addView(buttonRow(
            "上报一次" to { runMgmtReport() },
            "拉取配置" to { runMgmtPull() },
            "设备ID" to { runShowDeviceId() }
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

    /** 所有输出统一走 LogBus，悬浮窗与主界面共享同一份日志。 */
    private fun appendLine(s: String = "") = LogBus.emit(s)

    private fun renderLine(s: String = "") {
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

    // ------------------------------------------------------------ 悬浮窗

    /**
     * 启动悬浮控制台。
     *
     * 悬浮窗权限（SYSTEM_ALERT_WINDOW）正常需要用户去设置里手动开，
     * 但我们有 root —— 直接用 `appops set` 自己授权，用户无感。
     */
    private fun startOverlay() {
        ShellCore.init(this)
        Thread {
            val ok = ensureOverlayPermission()
            runOnUiThread {
                if (!ok) {
                    appendLine()
                    appendLine("无法获取悬浮窗权限。请手动到「设置 → 应用 → P0探测 → 显示在其他应用上层」开启。")
                    toast("需要悬浮窗权限")
                    return@runOnUiThread
                }
                OverlayService.start(this)
                appendLine()
                appendLine("悬浮控制台已启动 —— 按钮会浮在游戏画面上，可直接点。")
                appendLine("悬浮窗里点「ROI显示」还能把识别框画在游戏画面上。")
                toast("悬浮窗已启动")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun stopOverlay() {
        OverlayService.stop(this)
        appendLine()
        appendLine("悬浮控制台已停止。")
        toast("悬浮窗已停止")
    }

    /** 优先用 root 自授权；失败则退回到拉起系统设置页。 */
    private fun ensureOverlayPermission(): Boolean {
        if (android.provider.Settings.canDrawOverlays(this)) return true
        if (ShellCore.ensureRoot()) {
            ShellCore.root.exec("appops set ${packageName} SYSTEM_ALERT_WINDOW allow", 8000)
            Thread.sleep(400)
            if (android.provider.Settings.canDrawOverlays(this)) return true
        }
        // 兜底：让用户手动开
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return false
    }

    // ------------------------------------------------------------ 集控

    private fun saveMgmtFields() {
        mgmt.server = mgmtField.text.toString()
        mgmt.token = mgmtToken.text.toString()
        mgmt.enabled = mgmtChk.isChecked
    }

    private fun runMgmtReport() {
        saveMgmtFields()
        mgmt.enabled = mgmtChk.isChecked
        appendLine()
        appendLine("--- 集控：上报一次 ---")
        background("集控上报") {
            val r = mgmt.reportOnce()
            runOnUiThread { r.split('\n').forEach { appendLine(it) } }
        }
    }

    private fun runMgmtPull() {
        saveMgmtFields()
        appendLine()
        appendLine("--- 集控：拉取配置 ---")
        background("集控拉配置") {
            val r = mgmt.pullConfigOnce()
            runOnUiThread { r.split('\n').forEach { appendLine(it) } }
        }
    }

    private fun runShowDeviceId() {
        appendLine()
        appendLine("设备ID: ${mgmt.deviceId}")
        appendLine("Token : ${mgmt.token.ifBlank { "(未填)" }}")
        appendLine("（把这个填进集控服务器的设备列表，即可按设备下发不同配置）")
    }

    /** 若勾选了集控，进入前台时启动上报循环。 */
    private fun maybeStartMgmt() {
        saveMgmtFields()
        if (mgmt.enabled) {
            if (mgmt.token.isBlank()) {
                appendLine("⚠ 集控已勾选启用，但 Token 为空 —— 服务器会返回 401。请从集控面板复制 Token 填入。")
            }
            mgmt.start()
        } else {
            mgmt.stop()
        }
    }

    // ------------------------------------------------------------ Root 权限

    /**
     * 申请 / 重新检测 root 权限。
     *
     * 已知现象：`pm install -r` 更新 APK 会杀掉本进程并重启，
     * su 的授权会话可能随之失效 —— 表现为「系统里明明给了 root，App 却拿不到」。
     * 这个按钮会重建 shell 并把 su 的原始输出报出来。
     */
    private fun runRequestRoot() {
        appendLine()
        appendLine("--- 申请 / 检测 root 权限 ---")
        appendLine("提示：若云手机屏幕上弹出授权框，请点「允许」，然后【再点一次本按钮】。")
        background("申请Root") {
            val r = ShellCore.probe.requestRoot()
            runOnUiThread { r.split('\n').forEach { appendLine(it) } }
        }
    }

    // ------------------------------------------------------------ 自更新

    /**
     * 自更新（多源）：依次尝试 gh-proxy → ghfast → GitHub 直连 → jsDelivr，
     * 找到版本更高的才装。因为 GitHub 在国内经常连不上，单源不可靠。
     */
    private fun runSelfUpdate() {
        updater.setAutoCheck(autoChk.isChecked)
        appendLine()
        appendLine("--- 自更新（多源自动切换）---")
        appendLine("源顺序: " + Updater.SOURCES.joinToString(" → ") { it.first })
        background("自更新") {
            val r = updater.updateAuto(forceChk.isChecked)
            runOnUiThread { r.split('\n').forEach { appendLine(it) } }
        }
    }

    /** 按输入框里的自定义 URL 更新（用于自建源/回滚到指定版本）。 */
    private fun runUpdateByUrl() {
        val url = urlField.text.toString().trim()
        if (url.isEmpty()) { toast("请先填更新源 URL"); return }
        updater.saveUrl(url)
        appendLine()
        appendLine("--- 按指定 URL 更新 ---")
        background("按URL更新") {
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
            // 默认不自动检查更新：只有用户在界面里勾选了才检查（并已提示）
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
        LogBus.dump().ifBlank { lastReport ?: "（还没有内容）" }

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
        runCatching { LogBus.remove(logListener) }
        runCatching { mgmt.stop() }
        // 注意：不在这里关闭 root shell —— 悬浮窗可能还在用同一个 ShellCore.root
    }
}
