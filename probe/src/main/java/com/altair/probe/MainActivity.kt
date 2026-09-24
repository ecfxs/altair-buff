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
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 主界面 —— 四页签
 * ================
 *
 *   运行  |  设置  |  更新  |  日志
 *
 * 设计取舍（都是"简化"这条线上的决定）：
 *  · **坐标一律在悬浮窗里标**：只有把点打在**真实的游戏画面**上才准，所以本页只做查看/清空，
 *    不放标注入口 —— 放了反而会有人在没有游戏画面的地方点，标出无意义的坐标。
 *  · **没有输入方式二选一**：只剩触摸通道（键盘通道实测数字键无响应，已整体删除）。
 *  · **没有集控页**：多设备群控已下线，更新独立成页并固定显示版本号，方便对版本排查问题。
 *  · 日志独立一页并**自动滚到最新**（用户手动往上翻时暂停自动滚动，否则正读着就被拽走）。
 */
class MainActivity : Activity() {

    private val probe get() = ShellCore.probe
    private val updater get() = ShellCore.updater

    // ---- 页签 ----
    private lateinit var tabButtons: List<TextView>
    private lateinit var pages: List<View>
    private var tab = 0

    // ---- 运行页 ----
    private lateinit var runStatus: TextView
    private lateinit var runLog: TextView

    // ---- 设置页 ----
    private lateinit var targetField: EditText
    private lateinit var legEdit: EditText
    private lateinit var intervalEdit: EditText
    private lateinit var pushEdit: EditText
    private lateinit var jumpEdit: EditText

    /** BUFF 4 个槽位：(勾选框, 时长秒输入框)。槽位下标 = 技能图标序号。 */
    private val buffRows = mutableListOf<Pair<CheckBox, EditText>>()

    // ---- 更新页 ----
    private lateinit var urlField: EditText
    private lateinit var autoChk: CheckBox
    private lateinit var forceChk: CheckBox
    private var versionTv: TextView? = null

    // ---- 全局操作状态条 ----
    private lateinit var busyStrip: TextView
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastRunRefresh = 0L
    private val tick = object : Runnable {
        override fun run() {
            val l = Busy.current
            busyStrip.text =
                if (l == null) "就绪" else "⏳ $l   ${"%.1f".format(Busy.elapsedMs() / 1000.0)}s"
            busyStrip.setBackgroundColor(Color.parseColor(if (l == null) "#12331F" else "#33301A"))
            busyStrip.setTextColor(Color.parseColor(if (l == null) "#7FD18B" else "#FFD479"))
            // 引擎在跑时，让运行页的倒计时也实时走
            if (tab == 0 && Engine.isRunning && System.currentTimeMillis() - lastRunRefresh > 900) {
                lastRunRefresh = System.currentTimeMillis()
                refreshRunStatus()
            }
            ticker.postDelayed(this, 250)
        }
    }

    // ---- 日志页 ----
    private lateinit var logScroll: ScrollView
    private lateinit var logBody: LinearLayout
    private var autoScroll = true
    private val logBuffer = StringBuilder()

    private val logListener: (String) -> Unit = { line -> runOnUiThread { renderLog(line) } }

    // ============================================================ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShellCore.init(this)
        // ★ 必须早于任何读引擎配置的代码（否则「更新后一打开就闪退」）
        Engine.init(this)
        WalkFlow.init(this)
        buildUi()
        LogBus.add(logListener)
        // 把之前累积的日志倒进来
        LogBus.dump().lines().forEach { if (it.isNotEmpty()) renderLog(it) }
        log("阿尔泰挂机 v" + updater.currentVersionName() + "（简化版：手动标注 + 定时补 BUFF + 定时走位）")
        log("包名 $packageName · 目标游戏 ${OverlayService.targetPkgOf(this)}")
        val miss = Picks.missing(this)
        if (miss.isEmpty()) {
            log("坐标标注：已齐全（技能1-4 / 跳跃 / 轮盘）")
        } else {
            log("坐标标注：还缺 ${miss.joinToString("、")} —— 点「启动悬浮窗」后切到游戏逐项标注")
        }
        switchTab(0)
        startupUpdateCheck()
        refreshRunStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { LogBus.remove(logListener) }
    }

    // ============================================================ 界面骨架

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1216"))
        }
        root.addView(buildTabBar())
        busyStrip = TextView(this).apply {
            text = "就绪"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(busyStrip)
        ticker.post(tick)

        val container = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        pages = listOf(buildRunPage(), buildSettingsPage(), buildUpdatePage(), buildLogPage())
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
        tabButtons = listOf("运行", "设置", "更新", "日志").mapIndexed { i, name ->
            TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(9), 0, dp(9))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = if (i < 3) dp(6) else 0 }
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
        if (i == 3) logScroll.post { if (autoScroll) logScroll.fullScroll(View.FOCUS_DOWN) }
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
            "▶ 启动任务" to { startEngine() },
            "⏹ 停止任务" to { stopEngine() }
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
                "2. 悬浮窗上点「标技能1」，再点画面上技能1的图标；技能2/3/4 同理\n" +
                "3. 点「标跳跃」点游戏里的跳跃键；点「标轮盘」点左下角摇杆正中心\n" +
                "4. 回本页点「▶ 启动任务」（标完会自动保存，下次开软件直接沿用）\n" +
                "5. 启动后：立刻补一次 BUFF + 走一次位；之后 BUFF 按各自时长补，" +
                "走位每 15 分钟一次\n\n" +
                "⚠ 引擎有前台门禁：只有目标游戏在前台才动手。所以在**本页**点「启动任务」后，" +
                "动作要等你切回游戏才开始（最多等 5 秒）。想立刻开跑，" +
                "就在游戏里用悬浮窗的「▶ 启动」。\n\n" +
                "想确认点标得准不准：悬浮窗上按「回显:关」变成「回显:开」，" +
                "画面上就会画出所有标注点。"
        ))
        return sv
    }

    private fun refreshRunStatus() {
        Thread {
            // ★ 整体兜一层：后台线程里的未捕获异常会直接杀掉 App。
            //   历史上正是这里的 NPE 造成「更新后一打开就闪退」。
            runCatching {
                val fg = runCatching { ShellCore.probe.foregroundPackage() }.getOrDefault("")
                val target = OverlayService.targetPkgOf(this)
                val armed = fg == target
                val missing = Picks.missing(this)
                val txt = buildString {
                    append(if (armed) "🟢 游戏中 · 动作已启用" else "🔴 非游戏 · 动作已禁用").append('\n')
                    append("── 挂机任务 ──").append('\n')
                    append("状态      : ").append(Engine.stateText()).append('\n')
                    append("下次补BUFF: ").append(Engine.countdown(Engine.nextBuffDueAt)).append('\n')
                    append("下次走位  : ").append(Engine.countdown(Engine.nextWalkDueAt)).append('\n')
                    append("累计      : 补 ").append(Engine.buffCastCount)
                        .append(" 次 / 走 ").append(Engine.walkCount).append(" 次")
                    if (Engine.failStreak > 0) append("（连续失败 ${Engine.failStreak}）")
                    append('\n')
                    if (Engine.lastResult.isNotBlank()) append("上次结果  : ").append(Engine.lastResult).append('\n')
                    if (Engine.lastWalkResult.isNotBlank()) append("上次走位  : ").append(Engine.lastWalkResult).append('\n')
                    if (Engine.lastError.isNotBlank()) append("最近错误  : ").append(Engine.lastError).append('\n')
                    append("── 标注 ──").append('\n')
                    append("技能图标  : ").append(
                        if (Picks.skillsReady(this@MainActivity)) "✅ 技能1-4 已标注"
                        else "❌ 缺 " + Picks.missingSkills(this@MainActivity).joinToString("、")
                    ).append('\n')
                    append("跳跃      : ").append(
                        if (Picks.get(this@MainActivity, Picks.JUMP) != null) "✅ 已标注"
                        else "❌ 未标注（走位不会跳）"
                    ).append('\n')
                    append("轮盘中心  : ").append(
                        if (Picks.joystickAnnotated(this@MainActivity)) "✅ 已标注"
                        else "⚠ 未标注（用默认左下角）"
                    ).append('\n')
                    if (missing.isNotEmpty()) append("还缺      : ").append(missing.joinToString("、")).append('\n')
                    append("── 环境 ──").append('\n')
                    append("前台应用  : ").append(if (fg.isBlank()) "未知" else fg).append('\n')
                    append("目标游戏  : ").append(target).append('\n')
                    append("悬浮窗    : ").append(if (OverlayService.running) "运行中" else "未启动").append('\n')
                    append("当前操作  : ").append(Busy.current ?: "空闲")
                }
                runOnUiThread {
                    runStatus.text = txt
                    versionTv?.text = versionText()
                }
            }.onFailure {
                LogBus.emit("刷新运行状态失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startEngine() {
        // 先把已有标注读出来，失败原因能说得具体一点
        val miss = Picks.missingSkills(this)
        if (miss.isNotEmpty()) {
            log("⛔ 启动失败：技能图标还没标注全（缺 ${miss.joinToString("、")}）。")
            toast("请先在悬浮窗标注技能图标")
            switchTab(1)
            return
        }
        if (Engine.buffConfig().none { it.enabled }) {
            log("⛔ 启动失败：4 个 BUFF 槽位一个都没勾选。请到「设置」页勾选。")
            toast("请先在设置页勾选 BUFF")
            switchTab(1)
            return
        }
        Engine.start(this)
        if (Engine.isRunning) switchTab(3)   // 启动成功就跳到日志页，能直接看到动作
        refreshRunStatus()
    }

    private fun stopEngine() {
        Engine.stop()
        refreshRunStatus()
    }

    // ============================================================ 设置页

    private fun buildSettingsPage(): View {
        val sv = ScrollView(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        sv.addView(c)

        // ---------- BUFF 4 个槽位 ----------
        val buffBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in 0 until Picks.SKILL_COUNT) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val en = CheckBox(this).apply {
                text = "BUFF ${i + 1}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#C9D4E0"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val dur = EditText(this).apply {
                setText("${Engine.DEFAULT_DUR_SEC}")
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#C9D4E0"))
                setBackgroundColor(Color.parseColor("#0B0E12"))
                layoutParams = LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(6) }
            }
            val unit = TextView(this).apply {
                text = " 秒"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#8FA3B8"))
            }
            r.addView(en); r.addView(dur); r.addView(unit)
            buffBox.addView(r)
            buffRows.add(en to dur)
        }
        loadBuffConfig()
        c.addView(card("BUFF 技能（勾选要补的，时长决定多久补一次）", buffBox))
        c.addView(row("保存 BUFF 配置" to { saveBuffConfig() }))
        c.addView(note(
            "4 个槽位对应标注的「技能1..4」：勾上 BUFF2 就会去点技能2的图标。\n" +
                "每个槽位**各自独立计时**（填 280 秒就大约每 263 秒补一次，留了 6% 余量），" +
                "所以不同技能时长不一样也没关系。没勾的槽位不点。"
        ))

        // ---------- 原地走位 ----------
        c.addView(section("原地走位（左 D → 右 2D → 左 D → 跳一下）"))
        legEdit = EditText(this).apply {
            hint = "单程时长 D（毫秒），默认 600"
            setText(WalkFlow.legMs.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            styleEdit()
        }
        c.addView(legEdit)
        intervalEdit = EditText(this).apply {
            hint = "走位间隔（分钟），默认 15"
            setText(WalkFlow.intervalMin.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            styleEdit()
        }
        c.addView(intervalEdit)
        pushEdit = EditText(this).apply {
            hint = "推杆幅度（屏幕宽度百分比），默认 6"
            setText(WalkFlow.pushPct.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            styleEdit()
        }
        c.addView(pushEdit)
        jumpEdit = EditText(this).apply {
            hint = "跳跃按压时长（毫秒），默认 90"
            setText(WalkFlow.jumpPressMs.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            styleEdit()
        }
        c.addView(jumpEdit)
        c.addView(buttonRow("保存走位参数" to { saveWalk() }))
        c.addView(note(
            "三段都从标注的「轮盘中心」推杆，1:2:1 的配比保证走完回到起点附近。\n" +
                "走得太少 → 调大单程时长或推杆幅度；走过头 → 调小。改完可在悬浮窗点「试走位一次」验证。"
        ))

        // ---------- 坐标标注 ----------
        c.addView(section("坐标标注"))
        c.addView(buttonRow(
            "查看标注" to { showPicks() },
            "清空标注" to { clearPicks() }
        ))
        c.addView(note(
            "标注必须在**游戏画面**上做（只有那样点出来的坐标才是准的），" +
                "所以入口在悬浮窗上：「标技能1..4」「标跳跃」「标轮盘」。\n" +
                "每一项独立保存，改一个不会动到其它项；下次开软件自动沿用上一次的标注。"
        ))

        // ---------- 目标游戏门禁 ----------
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
        c.addView(note("只有这个包名在前台时才执行动作。包名不对就不会有任何点击 —— 这是防" +
            "「在错误界面上乱点」的安全闸，换游戏时记得重新标定。"))

        return sv
    }

    // ---- BUFF 配置 ----

    private fun buffPrefs() = getSharedPreferences("buff", Context.MODE_PRIVATE)

    private fun loadBuffConfig() {
        val sp = buffPrefs()
        buffRows.forEachIndexed { i, (en, dur) ->
            en.isChecked = sp.getBoolean("enabled$i", i == 0)
            // 新键是秒；旧键 dur$i 存的是分钟 → ×60 迁移（复用同键会把 5 分钟读成 5 秒）
            val sec = sp.getInt("durSec$i", -1).let { v ->
                if (v > 0) v else sp.getInt("dur$i", 0).takeIf { it > 0 }?.times(60) ?: Engine.DEFAULT_DUR_SEC
            }
            dur.setText(sec.toString())
        }
    }

    private fun saveBuffConfig() {
        val sp = buffPrefs().edit()
        buffRows.forEachIndexed { i, (en, dur) ->
            sp.putBoolean("enabled$i", en.isChecked)
            sp.putInt(
                "durSec$i",
                dur.text.toString().toIntOrNull()?.coerceIn(10, 86_400) ?: Engine.DEFAULT_DUR_SEC
            )
        }
        sp.apply()
        val enabled = buffRows.filter { it.first.isChecked }
        val detail = if (enabled.isEmpty()) {
            "⚠ 一个都没勾选，任务无法启动"
        } else {
            enabled.joinToString("、") { (en, dur) ->
                val i = buffRows.indexOfFirst { it === en }
                val sec = dur.text.toString().toIntOrNull() ?: Engine.DEFAULT_DUR_SEC
                "BUFF${i + 1} 每 ${"%.1f".format(Engine.slotPeriodMs(sec) / 60_000.0)} 分钟"
            }
        }
        log("BUFF 配置已保存：启用 ${enabled.size} 个 —— $detail")
        toast("已保存")
    }

    // ---- 走位参数 ----

    private fun saveWalk() {
        WalkFlow.legMs = legEdit.text.toString().toLongOrNull() ?: 600L
        WalkFlow.intervalMin = intervalEdit.text.toString().toLongOrNull() ?: 15L
        WalkFlow.pushPct = pushEdit.text.toString().toIntOrNull() ?: 6
        WalkFlow.jumpPressMs = jumpEdit.text.toString().toIntOrNull() ?: Picks.TAP_PRESS_MS
        // 回填被 coerce 过的值，让界面显示的就是真正生效的值
        legEdit.setText(WalkFlow.legMs.toString())
        intervalEdit.setText(WalkFlow.intervalMin.toString())
        pushEdit.setText(WalkFlow.pushPct.toString())
        jumpEdit.setText(WalkFlow.jumpPressMs.toString())
        val msg = "走位参数已保存：单程 ${WalkFlow.legMs}ms → 右 ${WalkFlow.legMs * 2}ms → 左 " +
            "${WalkFlow.legMs}ms → 跳；每 ${WalkFlow.intervalMin} 分钟一次，推杆 ${WalkFlow.pushPct}%"
        log(msg)
        toast("已保存")
    }

    // ---- 标注查看 / 清空 ----

    private fun showPicks() {
        val missing = Picks.missing(this)
        log("--- 已保存的标注（${Picks.ALL.size - missing.size}/${Picks.ALL.size} 项）---")
        Picks.describe(this).lines().forEach { log(it) }
        if (missing.isNotEmpty()) {
            log("  还缺：${missing.joinToString("、")} —— 在悬浮窗上逐项标注")
        }
    }

    private fun clearPicks() {
        Picks.clearAll(this)
        log("标注已清空（技能1-4 / 跳跃 / 轮盘）。任务下次启动前需要重新标注。")
        toast("已清空标注")
        refreshRunStatus()
    }

    // ============================================================ 更新页

    private fun buildUpdatePage(): View {
        val sv = ScrollView(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        sv.addView(c)

        // ---------- 版本（用户要求：这一页要显示软件版本）----------
        c.addView(card(
            "软件版本",
            TextView(this).apply {
                versionTv = this
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#C9D4E0"))
                text = versionText()
            }
        ))

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
        c.addView(note(
            "实测 GitHub 直连在国内经常不通，自更新会自动在 " +
                "gh-proxy → ghfast → GitHub → jsDelivr 之间切换。\n" +
                "「按URL更新」和「安装本地APK」共用上面那个输入框：前者填 URL，后者填本地 APK 路径。"
        ))
        return sv
    }

    /** 版本信息文案（页首卡片 + 需要时刷新）。 */
    private fun versionText(): String =
        "阿尔泰挂机 v${updater.currentVersionName()}（versionCode ${updater.currentVersionCode()}）\n" +
            "简化版 · 包名 $packageName\n" +
            "目标游戏 ${OverlayService.targetPkgOf(this)} · " +
            "标注 ${Picks.ALL.size - Picks.missing(this).size}/${Picks.ALL.size} 项"

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
            "申请Root" to { act("申请Root") { probe.requestRoot() } },
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
                s.contains("🔴") || s.contains("❌") -> Color.parseColor("#FF6B6B")
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
        runLog.text = logBuffer.toString().trimEnd().lines().takeLast(6).joinToString("\n")
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

    /**
     * 执行一个操作，并给出**三段式反馈**：开始 / 进度 / 结果。
     *
     * 同时做互斥：上一个还没跑完就再点别的，会直接提示而不是静默堆叠 ——
     * 否则两个操作同时抢 root shell，结果会互相穿插，根本看不懂。
     */
    private fun act(label: String, block: () -> String) {
        val running = Busy.current
        if (running != null) {
            toast("正在执行「$running」，请等它结束")
            log("⚠ 忽略「$label」：当前正在执行「$running」")
            return
        }
        val t0 = System.currentTimeMillis()
        Busy.begin(label)
        log("▸ $label")
        Thread {
            val res = runCatching { block() }
            val ms = System.currentTimeMillis() - t0
            Busy.end()
            runOnUiThread {
                if (res.isSuccess) {
                    res.getOrNull()?.split('\n')?.forEach { if (it.isNotBlank()) log(it) }
                    log("✅ $label 完成（${ms}ms）")
                    toast("✅ $label 完成")
                } else {
                    val e = res.exceptionOrNull()
                    log("❌ $label 失败（${ms}ms）：${e?.javaClass?.simpleName}: ${e?.message}")
                    toast("❌ $label 失败，看日志页")
                }
                refreshRunStatus()
            }
        }.apply { isDaemon = true }.start()
    }

    // ============================================================ 悬浮窗 / 门禁

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
                log("悬浮控制台已启动。切到游戏后即可逐项标注坐标。")
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

    // ============================================================ 更新

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

    // ============================================================ 日志导出

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
