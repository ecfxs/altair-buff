package com.altair.probe

import android.app.Activity
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
    /** 完整状态文本（现在收在「详细信息」折叠区里）。 */
    private lateinit var runStatus: TextView
    private lateinit var runLog: TextView
    /** 顶部状态徽章。 */
    private lateinit var runBadge: TextView
    /** 四个数字块。 */
    private var runBuffCd: TextView? = null
    private var runWalkCd: TextView? = null
    private var runBuffCount: TextView? = null
    private var runWalkCount: TextView? = null
    /** 实时主按钮（受状态驱动换文案）。 */
    private var runMainBtn: TextView? = null
    /** 上一轮前台门禁结果，供 tick 复用（免得每 250ms 查一次 shell）。 */
    @Volatile private var runArmed = false

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

    /**
     * 250ms 的界面心跳：状态条 + 运行页的倒计时 + 主按钮文案。
     *
     * 全部是**本地读属性**，不碰 root shell —— 所以可以按 250ms 跑。
     * 改造前这里每 900ms 调一次 `refreshRunStatus()`（那会去查前台包名），
     * 结果就是"为了跳个秒"而去起一次 shell 进程。
     */
    private val tick = object : Runnable {
        override fun run() {
            val l = Busy.current
            val want = if (l == null) "就绪" else "⏳ $l   ${"%.1f".format(Busy.elapsedMs() / 1000.0)}s"
            if (busyStrip.text != want) {
                busyStrip.text = want
                busyStrip.setBackgroundColor(if (l == null) Ui.SURFACE_2 else Ui.SURFACE_2)
                busyStrip.setTextColor(if (l == null) Ui.OK else Ui.WARN)
            }
            if (tab == 0) tickRunPage()
            ticker.postDelayed(this, 250)
        }
    }

    /**
     * 只更新随时间变化的东西：两个倒计时 + 主按钮文案。
     *
     * 刻意**不**在这里查前台包名或标注状态 —— 那些只在 [refreshRunStatus] 里查。
     */
    private fun tickRunPage() {
        val running = Engine.isRunning
        val now = System.currentTimeMillis()
        runBuffCd?.text = Ui.mmss(Engine.nextBuffDueAt - now, running)
        runWalkCd?.text = Ui.mmss(Engine.nextWalkDueAt - now, running)
        runBuffCount?.text = "${Engine.buffCastCount} 次"
        runWalkCount?.text = "${Engine.walkCount} 次"
        // 徽章的配色依赖 runArmed（上一次查询的结果），这里只做文案与颜色，不重新查
        runBadge.text = badgeText()
        runBadge.setTextColor(badgeColor())
        runMainBtn?.text = if (running) "⏹  停止任务" else "▶  启动任务"
    }

    /** 注意带"● "前缀 —— 徽章就是"圆点 + 文字"，颜色由 [badgeColor] 决定。 */
    private fun badgeText(): String = "● " + if (runArmed) "游戏中 · 动作已启用" else "非游戏 · 动作已禁用"

    private fun badgeColor(): Int = Ui.statusColor(
        running = Engine.isRunning,
        error = Engine.state == Engine.State.ERROR,
        armed = runArmed
    )

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
            setBackgroundColor(Ui.BG)
        }
        root.addView(buildTabBar())
        busyStrip = TextView(this).apply {
            text = "就绪"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(14), dp(7), dp(14), dp(7))
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

    private val tabNames = listOf("运行", "设置", "更新", "日志")

    /**
     * 顶部页签：选中项圆角高亮，未选中透明。
     *
     * 改造前是四个纯色方块，选中/未选中靠整块换色 —— 在深色底上像四个按钮而不是页签。
     * 现在选中项是一块圆角高亮，未选中只有文字变色，层级清楚得多。
     */
    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.BAR)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        tabButtons = tabNames.mapIndexed { i, name ->
            TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    // 最后一个不加右边距 —— 改造前写死 `i < 3`，页签数量一变就错
                    .apply { marginEnd = if (i < tabNames.size - 1) dp(6) else 0 }
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
            b.background = Ui.ripple(
                this,
                Ui.shape(this, if (on) Ui.PRIMARY else Color.TRANSPARENT, Ui.RADIUS_CTRL),
                Ui.RADIUS_CTRL
            )
            b.setTextColor(if (on) Color.WHITE else Ui.TEXT_DIM)
        }
        if (i == 3) logScroll.post { if (autoScroll) logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ============================================================ 运行页

    /**
     * 运行页。
     *
     * 改造前这一页把十几项信息（状态、两个倒计时、累计次数、三行标注情况、三行环境信息）
     * 拼成一个大字符串塞进一个 TextView —— 想看一眼"还有多久补 BUFF"得读完整段。
     * 现在拆成：**状态徽章一行 + 2x2 数字块 + 主按钮**，一眼能看到重点；
     * 原来那段完整文本收进「详细信息」折叠区，排查问题时还在。
     */
    private fun buildRunPage(): View {
        val sv = ScrollView(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        sv.addView(c)

        // ---------- 状态徽章 ----------
        runBadge = Ui.badge(this, Ui.TEXT_DIM, "读取中…")
        c.addView(Ui.card(this, null, runBadge))

        // ---------- 数字块 2x2 ----------
        c.addView(Ui.card(this, null, LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(statBlock("下次补BUFF", { runBuffCd = it }))
                addView(statBlock("下次走位", { runWalkCd = it }))
            })
            addView(Ui.divider(this@MainActivity, 8, 8))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(countBlock("已补 BUFF", { runBuffCount = it }))
                addView(countBlock("已走位", { runWalkCount = it }))
            })
        }))

        // ---------- 主按钮 ----------
        // 文案由 tickRunPage() 按引擎状态刷新，所以要把 TextView 存下来
        c.addView(Ui.btnRow(this,
            Triple("▶  启动任务", Ui.Kind.PRIMARY) {
                if (Engine.isRunning) stopEngine() else startEngine()
            }
        ).also { row -> runMainBtn = row.getChildAt(0) as TextView })
        c.addView(Ui.btnRow(this,
            Triple("启动悬浮窗", Ui.Kind.SECONDARY) { startOverlay() },
            Triple("停止悬浮窗", Ui.Kind.SECONDARY) { stopOverlay() }
        ))

        // ---------- 最近日志 ----------
        c.addView(Ui.card(this, "最近日志", TextView(this).apply {
            runLog = this
            text = "（暂无）"
            setTextColor(Ui.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            maxLines = 5
        }))

        // ---------- 使用说明（折叠，默认收起）----------
        val (helpHead, helpBody) = Ui.collapsible(this, "使用顺序")
        c.addView(helpHead)
        c.addView(helpBody)
        helpBody.addView(Ui.doc(this,
            "1. 点「启动悬浮窗」，切到游戏\n" +
                "2. 点悬浮球展开控制台 →「标技能1」→ 点画面上技能1的图标；技能2/3/4 同理\n" +
                "3. 「标跳跃」点游戏里的跳跃键；「标轮盘」点左下角摇杆正中心\n" +
                "4. 回本页点「启动任务」（标完自动保存，下次开软件直接沿用）\n" +
                "5. 启动后立刻补一次 BUFF + 走一次位；之后 BUFF 按各自时长补，走位默认每 15 分钟一次\n\n" +
                "⚠ 引擎有前台门禁：只有目标游戏在前台才动手。在**本页**点「启动任务」后，" +
                "动作要等你切回游戏才开始（最多等 5 秒）。想立刻开跑，" +
                "就在游戏里用悬浮窗的「启动」。\n\n" +
                "想确认点标得准不准：悬浮窗「工具」里把「回显」打开，画面上就会画出所有标注点。"
        ))

        // ---------- 详细信息（折叠，默认收起；原来那一大段完整状态）----------
        val (detailHead, detailBody) = Ui.collapsible(this, "详细信息")
        c.addView(detailHead)
        c.addView(detailBody)
        detailBody.addView(TextView(this).apply {
            runStatus = this
            text = "…"
            setTextColor(Ui.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        return sv
    }

    /** 数字块：上面小标签、下面大号等宽数字。 */
    private fun statBlock(label: String, sink: (TextView) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(Ui.text(this, label, 11f, Ui.TEXT_DIM))
        box.addView(Ui.text(this, "--:--", 22f, Ui.TEXT, mono = true).apply {
            setPadding(0, dp(4), 0, 0)
            sink(this)
        })
        return box
    }

    /** 计数块：上面小标签、下面一行"12 次"。 */
    private fun countBlock(label: String, sink: (TextView) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(Ui.text(this, label, 11f, Ui.TEXT_DIM))
        box.addView(Ui.text(this, "0 次", 13f, Ui.TEXT_DIM).apply {
            setPadding(0, dp(3), 0, 0)
            sink(this)
        })
        return box
    }

    /**
     * 拉一次完整状态。**这是唯一会去查前台包名的地方**（要走 root shell，几毫秒到几十毫秒）。
     *
     * 拆成两条路径后，倒计时的跳动不再依赖它 —— 见 [tickRunPage]。改造前整页每 900ms
     * 重查一次前台包名，纯属浪费：门禁结果只在动作发生那一刻要紧。
     *
     * ★ `Thread` + `runCatching` 这层兜底**不要动**：后台线程里的未捕获异常会直接杀掉
     *   App，历史上正是这里的 NPE 造成「更新后一打开就闪退」。
     */
    private fun refreshRunStatus() {
        Thread {
            runCatching {
                val fg = runCatching { ShellCore.probe.foregroundPackage() }.getOrDefault("")
                val target = OverlayService.targetPkgOf(this)
                val armed = fg == target
                runArmed = armed
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
                    // 数字块与徽章只在这里更新 —— 它们是"刚才那一刻的真值"
                    tickRunPage()
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
        // 每一行 = [勾选框] [标签] [时长输入框] [秒]
        // 输入框带**固定标签**而不是靠 hint —— 改造前 hint 一输入就消失，回头看不出这格是什么。
        val buffBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in 0 until Picks.SKILL_COUNT) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val en = CheckBox(this).apply {
                text = ""
                layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val label = Ui.text(this, "BUFF ${i + 1}", 12f, Ui.TEXT).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val dur = EditText(this).apply {
                setText("${Engine.DEFAULT_DUR_SEC}")
                inputType = InputType.TYPE_CLASS_NUMBER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Ui.TEXT)
                background = Ui.shape(this@MainActivity, Ui.LOGBG, Ui.RADIUS_CTRL, Ui.BORDER)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(6) }
            }
            r.addView(en); r.addView(label); r.addView(dur)
            r.addView(Ui.text(this, " 秒", 11f, Ui.TEXT_FAINT))
            buffBox.addView(r)
            buffRows.add(en to dur)
        }
        loadBuffConfig()
        c.addView(Ui.card(this, "BUFF 技能（勾选要补的，时长决定多久补一次）", buffBox))
        c.addView(Ui.btnRow(this, Triple("保存 BUFF 配置", Ui.Kind.PRIMARY) { saveBuffConfig() }))
        c.addView(Ui.doc(this,
            "4 个槽位对应标注的「技能1..4」：勾上 BUFF2 就会去点技能2的图标。\n" +
                "每个槽位**各自独立计时**（填 280 秒就大约每 263 秒补一次，留了 6% 余量），" +
                "所以不同技能时长不一样也没关系。没勾的槽位不点。"
        ))

        // ---------- 原地走位 ----------
        c.addView(Ui.section(this, "原地走位（左 D → 右 2D → 左 D → 跳一下）"))
        legEdit = newEdit(WalkFlow.legMs.toString())
        c.addView(Ui.labeledRow(this, "单程时长 D", legEdit, "毫秒 · 默认 600"))
        intervalEdit = newEdit(WalkFlow.intervalMin.toString())
        c.addView(Ui.labeledRow(this, "走位间隔", intervalEdit, "分钟 · 默认 15"))
        pushEdit = newEdit(WalkFlow.pushPct.toString())
        c.addView(Ui.labeledRow(this, "推杆幅度", pushEdit, "% 屏宽 · 默认 6"))
        jumpEdit = newEdit(WalkFlow.jumpPressMs.toString())
        c.addView(Ui.labeledRow(this, "跳跃按压", jumpEdit, "毫秒 · 默认 90"))
        c.addView(Ui.btnRow(this, Triple("保存走位参数", Ui.Kind.PRIMARY) { saveWalk() }))
        c.addView(Ui.doc(this,
            "三段都从标注的「轮盘中心」推杆，1:2:1 的配比保证走完回到起点附近。\n" +
                "走得太少 → 调大单程时长或推杆幅度；走过头 → 调小。\n" +
                "改完可在悬浮窗「工具」里点「试走位一次」验证。"
        ))

        // ---------- 坐标标注 ----------
        c.addView(Ui.section(this, "坐标标注"))
        c.addView(Ui.btnRow(this,
            Triple("查看标注", Ui.Kind.SECONDARY) { showPicks() },
            Triple("清空标注", Ui.Kind.GHOST) { clearPicks() }
        ))
        c.addView(Ui.doc(this,
            "标注必须在**游戏画面**上做（只有那样点出来的坐标才是准的），" +
                "所以入口在悬浮窗上：点悬浮球展开 →「标注」区里逐项标。\n" +
                "每一项独立保存，改一个不会动到其它项；下次开软件自动沿用上一次的标注。"
        ))

        // ---------- 目标游戏门禁 ----------
        c.addView(Ui.section(this, "门禁 · 目标游戏"))
        targetField = EditText(this).apply {
            hint = "com.example.game"
            setText(OverlayService.targetPkgOf(this@MainActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_FAINT)
            background = Ui.shape(this@MainActivity, Ui.LOGBG, Ui.RADIUS_CTRL, Ui.BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        c.addView(targetField)
        c.addView(Ui.btnRow(this,
            Triple("保存", Ui.Kind.SECONDARY) { saveTarget() },
            Triple("用当前前台标定", Ui.Kind.PRIMARY) { calibrateTarget() }
        ))
        c.addView(Ui.doc(this,
            "只有这个包名在前台时才执行动作。包名不对就不会有任何点击 —— 这是防" +
                "「在错误界面上乱点」的安全闸，换游戏时记得重新标定。"
        ))

        return sv
    }

    /** 一个统一风格的输入框（设置页用）。 */
    private fun newEdit(initial: String): EditText = EditText(this).apply {
        setText(initial)
        inputType = InputType.TYPE_CLASS_NUMBER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Ui.TEXT)
        gravity = Gravity.END
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
        c.addView(Ui.card(this, "软件版本", TextView(this).apply {
            versionTv = this
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Ui.TEXT)
            setLineSpacing(dp(4).toFloat(), 1f)
            text = versionText()
        }))

        c.addView(Ui.section(this, "更新源"))
        urlField = EditText(this).apply {
            hint = "自定义更新源 URL（留空用默认多源）"
            setText(updater.savedUrl())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_FAINT)
            background = Ui.shape(this@MainActivity, Ui.LOGBG, Ui.RADIUS_CTRL, Ui.BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        c.addView(urlField)
        val optRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        autoChk = CheckBox(this).apply {
            text = "启动时自动检查更新"
            isChecked = updater.autoCheck()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Ui.TEXT_DIM)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        forceChk = CheckBox(this).apply {
            text = "强制重装"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Ui.TEXT_DIM)
        }
        optRow.addView(autoChk); optRow.addView(forceChk)
        c.addView(optRow)
        // 三个入口分组：主路径（多源自更新）整行，另两个并排
        c.addView(Ui.btnFull(this, "自更新（多源容灾）", Ui.Kind.PRIMARY) { runSelfUpdate() })
        c.addView(Ui.btnRow(this,
            Triple("按 URL 更新", Ui.Kind.SECONDARY) { runUpdateByUrl() },
            Triple("安装本地 APK", Ui.Kind.SECONDARY) { runLocalInstall() }
        ))
        c.addView(Ui.doc(this,
            "实测 GitHub 直连在国内经常不通，自更新会自动在 " +
                "gh-proxy → ghfast → GitHub → jsDelivr 之间切换。\n" +
                "「按 URL 更新」和「安装本地 APK」共用上面那个输入框：前者填 URL，后者填本地 APK 路径。"
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
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        wrap.addView(Ui.btnRow(this,
            Triple("复制全部", Ui.Kind.SECONDARY) { copyLog() },
            Triple("导出文件", Ui.Kind.SECONDARY) { saveLog() },
            Triple("清空", Ui.Kind.GHOST) {
                logBuffer.setLength(0); logBody.removeAllViews(); LogBus.clear(); log("已清空。")
            }
        ))
        wrap.addView(Ui.btnRow(this,
            Triple("申请 Root", Ui.Kind.GHOST) { act("申请Root") { probe.requestRoot() } },
            Triple("自动滚动", Ui.Kind.GHOST) {
                autoScroll = !autoScroll
                log("日志自动滚动: " + if (autoScroll) "开" else "关（可自由上翻）")
                if (autoScroll) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            },
            Triple("滚到底部", Ui.Kind.GHOST) {
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        ))

        logScroll = ScrollView(this).apply {
            background = Ui.shape(this@MainActivity, Ui.LOGBG, Ui.RADIUS_CTRL, Ui.BORDER)
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
                s.startsWith("==") -> Ui.OK
                s.contains("★") || s.contains("⚠") ||
                    s.contains("!!") || s.contains("⛔") -> Ui.WARN
                s.contains("✅") || s.contains("🟢") -> Ui.OK
                s.contains("🔴") || s.contains("❌") -> Ui.DANGER
                else -> Ui.TEXT
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

    // 样式辅助函数（card / note / section / row / styleEdit）已经全部搬进 `Ui` 了 ——
    // 它们原本内联了几十个颜色字面量，同一角色在不同地方取值还不一样。见 Ui.kt。

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

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
