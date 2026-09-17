package com.altair.probe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 主界面 —— 正式版三页签结构
 * ==========================
 *
 *   运行  |  设置  |  日志
 *
 * 设计取舍：
 *  · 悬浮窗只留「ROI + 采点 + 技能键点击」这类**挂在游戏上天天要点的**；
 *    截图、诊断、键扫描、复制日志这些排查用的一次性工具都收进日志页。
 *  · 日志独立一页并且**自动滚到最新**（用户手动往上翻时暂停自动滚动，
 *    否则正读着就被拽走）。
 */
class MainActivity : Activity() {

    private val probe get() = ShellCore.probe
    private val updater get() = ShellCore.updater
    private val mgmt by lazy { Management(this, ShellCore.root) }

    // ---- 页签 ----
    private lateinit var tabButtons: List<TextView>
    private lateinit var pages: List<View>
    private var tab = 0

    // ---- 运行页 ----
    private lateinit var runStatus: TextView
    private lateinit var runLog: TextView

    // ---- 设置页 ----
    private lateinit var urlField: EditText
    private lateinit var autoChk: CheckBox
    private lateinit var forceChk: CheckBox
    private lateinit var mgmtField: EditText
    private lateinit var mgmtToken: EditText
    private lateinit var mgmtChk: CheckBox
    private lateinit var targetField: EditText
    private val buffRows = mutableListOf<Triple<CheckBox, Spinner, EditText>>()

    // ---- 日志页 ----
    private lateinit var logScroll: ScrollView
    private lateinit var logBody: LinearLayout
    private var autoScroll = true
    private val logBuffer = StringBuilder()
    private var lastReport: String? = null

    private val logListener: (String) -> Unit = { line -> runOnUiThread { renderLog(line) } }

    // ============================================================ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShellCore.init(this)
        buildUi()
        LogBus.add(logListener)
        // 把之前累积的日志倒进来
        LogBus.dump().lines().forEach { if (it.isNotEmpty()) renderLog(it) }
        log("阿尔泰挂机 v" + updater.currentVersionName())
        log("包名 ${packageName} · 目标游戏 ${OverlayService.targetPkgOf(this)}")
        switchTab(0)
        startupUpdateCheck()
        maybeStartMgmt()
        refreshRunStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { LogBus.remove(logListener) }
        runCatching { mgmt.stop() }
    }

    // ============================================================ 界面骨架

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1216"))
        }
        root.addView(buildTabBar())

        val container = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        pages = listOf(buildRunPage(), buildSettingsPage(), buildLogPage())
        pages.forEach { container.addView(it) }
        root.addView(container)
        setContentView(root)
    }

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#151A21"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        tabButtons = listOf("运行", "设置", "日志").mapIndexed { i, name ->
            TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(9), 0, dp(9))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = if (i < 2) dp(6) else 0 }
                setOnClickListener { switchTab(i) }
            }
        }
        tabButtons.forEach { bar.addView(it) }
        return bar
    }

    private fun switchTab(i: Int) {
        tab = i
        pages.forEachIndexed { idx, p -> p.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { idx, b ->
            val on = idx == i
            b.setBackgroundColor(Color.parseColor(if (on) "#2563EB" else "#1B2028"))
            b.setTextColor(Color.parseColor(if (on) "#FFFFFF" else "#8FA3B8"))
        }
        if (i == 2) logScroll.post { if (autoScroll) logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ============================================================ 运行页

    private fun buildRunPage(): View {
        val sv = ScrollView(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        sv.addView(c)

        c.addView(card(
            "运行状态",
            TextView(this).apply {
                runStatus = this
                text = "…"
                setTextColor(Color.parseColor("#C9D4E0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.MONOSPACE
                setLineSpacing(dp(3).toFloat(), 1f)
            }
        ))

        c.addView(row(
            "启动悬浮窗" to { startOverlay() },
            "停止悬浮窗" to { stopOverlay() }
        ))

        c.addView(card(
            "最近日志",
            TextView(this).apply {
                runLog = this
                text = "（暂无）"
                setTextColor(Color.parseColor("#8FA3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.MONOSPACE
                maxLines = 6
            }
        ))

        c.addView(note(
            "使用顺序\n" +
            "1. 点「启动悬浮窗」，切到游戏\n" +
            "2. 悬浮窗上点「ROI显示」核对识别框\n" +
            "3. 点「★采点」依次点：技能1-4 → 菜单按钮 → 自由市场按钮\n" +
            "4. 点「完成采点」后，用「点1..4」验证技能是否响应\n" +
            "5. 采到的坐标会自动保存，也可在「设置」页查看"
        ))
        return sv
    }

    private fun refreshRunStatus() {
        Thread {
            val fg = runCatching { ShellCore.probe.foregroundPackage() }.getOrDefault("")
            val target = OverlayService.targetPkgOf(this)
            val armed = fg == target
            val pts = OverlayService.pickedPointsOf(this)
            val txt = buildString {
                append(if (armed) "🟢 游戏中 · 动作已启用" else "🔴 非游戏 · 动作已禁用").append('\n')
                append("前台应用 : ").append(if (fg.isBlank()) "未知" else fg).append('\n')
                append("目标游戏 : ").append(target).append('\n')
                append("悬浮窗   : ").append(if (OverlayService.running) "运行中" else "未启动").append('\n')
                append("采集点   : ").append(pts.size).append(" 个")
                if (pts.size >= 4) append("（技能键已就绪）")
            }
            runOnUiThread { runStatus.text = txt }
        }.apply { isDaemon = true }.start()
    }

    // ============================================================ 设置页

    private fun buildSettingsPage(): View {
        val sv = ScrollView(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        sv.addView(c)

        // ---------- BUFF ----------
        val buffBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in 0 until 3) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val en = CheckBox(this).apply {
                text = "BUFF ${i + 1}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#C9D4E0"))
            }
            val key = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("数字键1", "数字键2", "数字键3", "数字键4"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val dur = EditText(this).apply {
                setText("5")
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#C9D4E0"))
                setBackgroundColor(Color.parseColor("#0B0E12"))
                layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(6) }
            }
            val unit = TextView(this).apply {
                text = " 分钟"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#8FA3B8"))
            }
            r.addView(en); r.addView(key); r.addView(dur); r.addView(unit)
            buffBox.addView(r)
            buffRows.add(Triple(en, key, dur))
        }
        loadBuffConfig()
        c.addView(card("BUFF 技能（按职业设 1~3 个，持续时间决定补的间隔）", buffBox))
        c.addView(row("保存 BUFF 配置" to { saveBuffConfig() }))

        // ---------- 坐标 ----------
        c.addView(buttonRow(
            "查看采集点" to { showPickedPoints() },
            "清空采集点" to { clearPickedPoints() }
        ))

        // ---------- 目标游戏 ----------
        targetField = EditText(this).apply {
            hint = "目标游戏包名"
            setText(OverlayService.targetPkgOf(this@MainActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#C9D4E0"))
            setBackgroundColor(Color.parseColor("#0B0E12"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        c.addView(section("门禁 · 目标游戏"))
        c.addView(targetField)
        c.addView(row(
            "保存" to { saveTarget() },
            "用当前前台标定" to { calibrateTarget() }
        ))

        // ---------- 集控 ----------
        c.addView(section("集控（多设备统一管理）"))
        mgmtField = EditText(this).apply {
            hint = "服务器地址，如 http://1.2.3.4:8899"
            setText(mgmt.server)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            styleEdit()
        }
        c.addView(mgmtField)
        mgmtToken = EditText(this).apply {
            hint = "设备 Token（集控面板页面上可复制）"
            setText(mgmt.token)
            styleEdit()
        }
        c.addView(mgmtToken)
        mgmtChk = CheckBox(this).apply {
            text = "启用集控定期上报（设备主动上报 + 拉配置）"
            isChecked = mgmt.enabled
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#8FA3B8"))
        }
        c.addView(mgmtChk)
        c.addView(buttonRow(
            "上报一次" to { runMgmtReport() },
            "拉取配置" to { runMgmtPull() },
            "设备ID" to { showDeviceId() }
        ))

        // ---------- 更新 ----------
        c.addView(section("更新"))
        urlField = EditText(this).apply {
            hint = "自定义更新源 URL（留空用默认多源）"
            setText(updater.savedUrl())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            styleEdit()
        }
        c.addView(urlField)
        val optRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        autoChk = CheckBox(this).apply {
            text = "启动时自动检查更新"
            isChecked = updater.autoCheck()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#8FA3B8"))
        }
        forceChk = CheckBox(this).apply {
            text = "强制重装"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#8FA3B8"))
        }
        optRow.addView(autoChk); optRow.addView(forceChk)
        c.addView(optRow)
        c.addView(buttonRow(
            "自更新(多源)" to { runSelfUpdate() },
            "按URL更新" to { runUpdateByUrl() },
            "安装本地APK" to { runLocalInstall() }
        ))
        c.addView(note("实测 GitHub 直连在国内经常不通，自更新会自动在 " +
            "gh-proxy → ghfast → GitHub → jsDelivr 之间切换。"))
        return sv
    }

    // ============================================================ 日志页

    private fun buildLogPage(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        wrap.addView(buttonRow(
            "复制全部" to { copyLog() },
            "导出到文件" to { saveLog() },
            "清空" to {
                logBuffer.setLength(0); logBody.removeAllViews(); LogBus.clear(); log("已清空。")
            }
        ))
        wrap.addView(buttonRow(
            "截图" to { act("截图") { probe.quickCapture() } },
            "按键诊断" to { act("按键诊断") { probe.keyDiagnostics(3) } },
            "键扫描" to { act("键扫描") { probe.keyScan(3) } },
            "申请Root" to { act("申请Root") { probe.requestRoot() } }
        ))
        wrap.addView(buttonRow(
            "采集压测" to { act("采集压测") { probe.benchCapture() } },
            "自动滚动开关" to {
                autoScroll = !autoScroll
                log("日志自动滚动: " + if (autoScroll) "开" else "关（可自由上翻）")
                if (autoScroll) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            },
            "滚到底部" to { logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) } }
        ))

        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0E12"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setOnScrollChangeListener { _, _, _, _, _ ->
                // 用户手动往上翻时暂停自动滚动，否则正读着就被拽走
                val atBottom = (getChildAt(0)?.height ?: 0) - height - scrollY < dp(40)
                if (!atBottom) autoScroll = false
            }
        }
        logBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        logScroll.addView(logBody)
        wrap.addView(logScroll)
        return wrap
    }

    private fun renderLog(s: String) {
        logBuffer.append(s).append('\n')
        if (logBuffer.length > 300_000) logBuffer.delete(0, 100_000)
        val tv = TextView(this).apply {
            text = s
            setTextColor(when {
                s.startsWith("==") -> Color.parseColor("#7FD18B")
                s.contains("★") || s.contains("⚠") ||
                    s.contains("!!") || s.contains("⛔") -> Color.parseColor("#FFB454")
                s.contains("✅") || s.contains("🟢") -> Color.parseColor("#7FD18B")
                s.contains("🔴") -> Color.parseColor("#FF6B6B")
                else -> Color.parseColor("#C9D4E0")
            })
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logBody.addView(tv)
        // 只保留最近 800 行，避免长时间运行后 View 数量爆炸
        while (logBody.childCount > 800) logBody.removeViewAt(0)
        if (autoScroll) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        runLog?.text = logBuffer.toString().trimEnd().lines().takeLast(6).joinToString("\n")
    }

    private fun log(s: String) = LogBus.emit(s)

    // ============================================================ 小工具

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun EditText.styleEdit() {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(Color.parseColor("#C9D4E0"))
        setHintTextColor(Color.parseColor("#5A6675"))
        setBackgroundColor(Color.parseColor("#0B0E12"))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) }
    }

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#8B97A6"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun card(title: String, body: View): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151A21"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#2C333D"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#8B97A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, dp(6))
        })
        box.addView(body)
        return box
    }

    private fun note(s: String): TextView = TextView(this).apply {
        text = s
        setTextColor(Color.parseColor("#6C7787"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(10), 0, 0)
    }

    private fun row(vararg btns: Pair<String, () -> Unit>): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        btns.forEach { (label, fn) ->
            r.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(3), 0, dp(3), 0)
                minWidth = 0
                minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
                    .apply { marginEnd = dp(5) }
                setOnClickListener { fn() }
            })
        }
        return r
    }

    private fun buttonRow(vararg btns: Pair<String, () -> Unit>): LinearLayout = row(*btns)

    private fun act(label: String, block: () -> String) {
        log("▸ $label")
        Thread {
            val r = runCatching { block() }
                .getOrElse { "出错: ${it.javaClass.simpleName}: ${it.message}" }
            runOnUiThread { r.split('\n').forEach { log(it) } }
        }.apply { isDaemon = true }.start()
    }

    // ============================================================ 各功能

    private fun startOverlay() {
        ShellCore.init(this)
        Thread {
            val ok = ensureOverlayPermission()
            runOnUiThread {
                if (!ok) {
                    log("无法获取悬浮窗权限。请到「设置 → 应用 → 显示在其他应用上层」手动开启。")
                    toast("需要悬浮窗权限")
                    return@runOnUiThread
                }
                OverlayService.start(this)
                log("悬浮控制台已启动（面板只含 ROI 与技能键点击）。")
                switchTab(2)
                refreshRunStatus()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun stopOverlay() {
        OverlayService.stop(this)
        log("悬浮控制台已停止。")
        refreshRunStatus()
    }

    private fun ensureOverlayPermission(): Boolean {
        if (android.provider.Settings.canDrawOverlays(this)) return true
        if (ShellCore.ensureRoot()) {
            ShellCore.root.exec("appops set $packageName SYSTEM_ALERT_WINDOW allow", 8000)
            Thread.sleep(400)
            if (android.provider.Settings.canDrawOverlays(this)) return true
        }
        runCatching {
            startActivity(Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }

    private fun saveTarget() {
        val v = targetField.text.toString().trim()
        if (v.isBlank()) { toast("不能为空"); return }
        OverlayService.setTargetPkgOf(this, v)
        log("目标游戏已设为 $v")
        refreshRunStatus()
    }

    private fun calibrateTarget() {
        Thread {
            val fg = ShellCore.probe.foregroundPackage()
            runOnUiThread {
                if (fg.isBlank() || fg == packageName) {
                    log("标定失败：当前前台是「${fg.ifBlank { "未知" }}」，请先切到游戏再点。")
                } else {
                    OverlayService.setTargetPkgOf(this, fg)
                    targetField.setText(fg)
                    log("目标游戏已标定为 $fg")
                    refreshRunStatus()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun showPickedPoints() {
        val pts = OverlayService.pickedPointsOf(this)
        log("--- 已保存的采集点（${pts.size} 个）---")
        if (pts.isEmpty()) log("（空，请到悬浮窗点「★采点」）")
        val names = arrayOf("技能1", "技能2", "技能3", "技能4", "菜单", "自由市场", "传送点", "备用")
        pts.forEachIndexed { i, (x, y) ->
            log("  %-6s = [%.4f, %.4f]".format(names.getOrElse(i) { "点${i + 1}" }, x, y))
        }
    }

    private fun clearPickedPoints() {
        OverlayService.savePickedPointsOf(this, emptyList())
        log("采集点已清空。")
        refreshRunStatus()
    }

    // ---- BUFF 配置（存 SharedPreferences，供后续 P1 引擎读取）----

    private fun buffPrefs() = getSharedPreferences("buff", Context.MODE_PRIVATE)

    private fun loadBuffConfig() {
        val sp = buffPrefs()
        buffRows.forEachIndexed { i, (en, key, dur) ->
            en.isChecked = sp.getBoolean("enabled$i", i == 0)
            key.setSelection(sp.getInt("key$i", i).coerceIn(0, 3))
            dur.setText(sp.getInt("dur$i", 5).toString())
        }
    }

    private fun saveBuffConfig() {
        val sp = buffPrefs().edit()
        buffRows.forEachIndexed { i, (en, key, dur) ->
            sp.putBoolean("enabled$i", en.isChecked)
            sp.putInt("key$i", key.selectedItemPosition)
            sp.putInt("dur$i", dur.text.toString().toIntOrNull()?.coerceIn(1, 240) ?: 5)
        }
        sp.apply()
        val enabled = buffRows.count { it.first.isChecked }
        val durs = buffRows.filter { it.first.isChecked }
            .map { it.third.text.toString().toIntOrNull() ?: 5 }
        val cycle = durs.minOrNull() ?: 0
        log("BUFF 配置已保存：启用 $enabled 个" +
            (if (cycle > 0) "，最短时长 ${cycle} 分钟 → 建议循环周期 ${(cycle * 0.94).toInt()} 分钟" else ""))
    }

    // ---- 集控 ----

    private fun saveMgmtFields() {
        mgmt.server = mgmtField.text.toString()
        mgmt.token = mgmtToken.text.toString()
        mgmt.enabled = mgmtChk.isChecked
    }

    private fun runMgmtReport() {
        saveMgmtFields()
        act("集控上报") { mgmt.reportOnce() }
    }

    private fun runMgmtPull() {
        saveMgmtFields()
        act("集控拉配置") { mgmt.pullConfigOnce() }
    }

    private fun showDeviceId() {
        log("设备ID: ${mgmt.deviceId}")
        log("Token : ${mgmt.token.ifBlank { "(未填)" }}")
    }

    private fun maybeStartMgmt() {
        saveMgmtFields()
        if (mgmt.enabled) {
            if (mgmt.token.isBlank()) {
                log("⚠ 集控已启用但 Token 为空，服务器会返回 401。请从集控面板复制 Token 填入。")
            }
            mgmt.start()
        } else mgmt.stop()
    }

    // ---- 更新 ----

    private fun runSelfUpdate() {
        updater.setAutoCheck(autoChk.isChecked)
        updater.saveUrl(urlField.text.toString())
        log("--- 自更新（多源）---")
        log("源顺序: " + Updater.SOURCES.joinToString(" → ") { it.first })
        act("自更新") { updater.updateAuto(forceChk.isChecked) }
    }

    private fun runUpdateByUrl() {
        val u = urlField.text.toString().trim()
        if (u.isEmpty()) { toast("请先填 URL"); return }
        updater.saveUrl(u)
        act("按URL更新") { updater.updateFromUrl(u, forceChk.isChecked) }
    }

    private fun runLocalInstall() {
        val p = urlField.text.toString().trim()
        if (p.isEmpty()) { toast("请填本地 APK 路径"); return }
        act("安装本地APK") { updater.installLocal(p, forceChk.isChecked) }
    }

    private fun startupUpdateCheck() {
        val auto = updater.autoCheck()
        Thread {
            runCatching {
                if (updater.hasUpdateLog()) {
                    val lg = updater.readUpdateLog()
                    runOnUiThread {
                        log("--- 上次自更新结果 ---")
                        lg.split('\n').takeLast(10).forEach { log(it) }
                    }
                }
            }
            if (!auto) return@Thread
            runCatching {
                val r = updater.updateAuto(false)
                runOnUiThread { log("--- 启动自动检查更新 ---"); r.split('\n').forEach { log(it) } }
            }
        }.apply { isDaemon = true }.start()
    }

    // ---- 日志导出 ----

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("altair", logBuffer.toString()))
        toast("已复制 ${logBuffer.length} 字")
    }

    private fun saveLog() {
        runCatching {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = File(dir, "altair_log.txt")
            f.writeText(logBuffer.toString())
            log("日志已保存: ${f.absolutePath}")
            toast("已保存")
        }.onFailure { toast("保存失败: ${it.message}") }
    }
}
