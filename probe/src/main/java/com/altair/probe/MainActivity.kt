package com.altair.probe

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 主界面
 * ======
 *
 * 三个页签 + **一条常驻底部操作条**：
 * ```
 *   任务  设置  更多
 *   …
 *   ● 运行中  补 04:12 · 走 12:30 · 技能 3/走位 1        [停止任务]
 * ```
 *
 * ## 为什么状态与启停要常驻
 * 改造前启停按钮只在「任务」页里，用户去「设置」改完一个参数想停任务，得先切回任务页。
 * 挂机软件最要紧的两个动作（看状态、停）现在任何一页都够得着。
 *
 * ## 为什么「更多」页把日志放在最后但高度固定
 * 日志曾放在页面最底部、高度写死 300dp，结果在 720p 横屏上要先滚过一大堆按钮才能看到
 * 日志的第一行。现在日志高度随屏幕算，并且自动跟到最新一行。
 *
 * 标记位置仍然只能在游戏画面的悬浮窗里完成 —— 那是唯一能拿到真实游戏画面的地方。
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val updater get() = ShellCore.updater

    private lateinit var pages: List<View>
    private lateinit var tabs: List<Button>

    // ---- 底部常驻操作条 ----
    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var metrics: TextView
    private lateinit var startButton: Button

    // ---- 任务页 ----
    private lateinit var status: TextView
    private lateinit var countdown: TextView
    private lateinit var readiness: TextView
    private lateinit var recent: TextView

    // ---- 设置页 ----
    private val skillRows = mutableListOf<Pair<CheckBox, EditText>>()
    private lateinit var leg: EditText
    private lateinit var interval: EditText
    private lateinit var push: EditText
    private lateinit var jump: EditText
    private lateinit var target: EditText

    // ---- 更多页 ----
    private lateinit var logs: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var sourceUrl: EditText
    private lateinit var markCount: TextView
    private var logTail = ""

    private val logListener: (String) -> Unit = { line -> ui.post {
        if (!isDestroyed) {
            logTail = (logTail + line + "\n").takeLast(32_000)
            logs.text = logTail
            recent.text = line
            scrollLogToEnd()
        }
    } }

    /** 长操作（检查 Root / 更新）的进度回显 —— 没有它用户只能看到一句 Toast 然后干等。 */
    private val busyListener: (String?) -> Unit = { ui.post { if (!isDestroyed) refresh() } }

    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        root.addView(header())
        val body = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        pages = listOf(home(), settings(), more())
        pages.forEach { body.addView(it) }
        root.addView(body)
        root.addView(bottomBar())
        setContentView(root)

        showPage(0)
        logTail = LogBus.dump().takeLast(32_000)
        logs.text = logTail
        LogBus.add(logListener)
        Busy.add(busyListener)
        LogBus.emit("v${updater.currentVersionName()} · 左 / 右 / 左回位后等待 1 秒跳一次")
        refresh()
    }

    override fun onStart() { super.onStart(); ui.post(tick) }

    override fun onStop() { ui.removeCallbacks(tick); super.onStop() }

    override fun onDestroy() {
        LogBus.remove(logListener)
        Busy.remove(busyListener)
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------ 骨架

    /**
     * 顶栏：标题与页签**同一行**。
     *
     * 720p 横屏的可用高度本来就紧，把"标题一行 + 页签一行"压成一行，等于白送一整行内容区。
     */
    private fun header(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.BAR)
            setPadding(dp(16), dp(8), dp(12), dp(8))
        }
        bar.addView(Ui.text(this, "阿尔泰", 17f, bold = true))
        bar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        tabs = listOf("任务", "设置", "更多").mapIndexed { i, title ->
            Ui.btn(this, title, Ui.Kind.SECONDARY, 13f, 36) { showPage(i) }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(76), dp(36)).apply {
                    marginStart = if (i == 0) 0 else dp(6)
                }
                bar.addView(this)
            }
        }
        return bar
    }

    /** 底部常驻操作条：状态 + 倒计时 + 启停，任何一页都够得着。 */
    private fun bottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BAR)
        }
        bar.addView(Ui.divider(this, 0, 0))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = Ui.dot(this, Ui.TEXT_DIM)
        statusText = Ui.text(this, "未启动", 14f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(6), 0, 0, 0)
        }
        line.addView(statusDot)
        line.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        metrics = Ui.text(this, "—", 11f, Ui.TEXT_FAINT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), dp(2), 0, 0)
        }
        left.addView(line)
        left.addView(metrics)
        row.addView(left)
        startButton = Ui.btn(this, "启动任务", Ui.Kind.PRIMARY, 15f, 44) { toggleEngine() }
        startButton.layoutParams = LinearLayout.LayoutParams(dp(112), dp(44)).apply { marginStart = dp(12) }
        row.addView(startButton)
        bar.addView(row)
        return bar
    }

    private fun showPage(index: Int) {
        pages.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, b -> Ui.paint(b, if (i == index) Ui.Kind.PRIMARY else Ui.Kind.SECONDARY) }
    }

    /**
     * 一页 = 可选的一行固定操作条 + 可滚动内容。
     *
     * 固定操作条用来放"这一页最常用的那一颗按钮"，免得用户为了点它滚到底。
     */
    private fun page(actionBar: View? = null, block: LinearLayout.() -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }
        if (actionBar != null) {
            actionBar.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            col.addView(actionBar)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(20))
                block()
            })
        }
        col.addView(scroll)
        return col
    }

    // ------------------------------------------------------------ 任务页

    private fun home(): View = page {
        val summary = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            status = Ui.text(context, "未启动", 20f, bold = true)
            addView(status)
            countdown = Ui.text(context, "", 13f, Ui.TEXT_DIM).apply {
                setPadding(0, dp(10), 0, 0)
                setLineSpacing(dp(5).toFloat(), 1f)
            }
            addView(countdown)
        }
        addView(Ui.card(this@MainActivity, "当前状态", summary))

        val guide = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        readiness = Ui.text(this@MainActivity, "", 13f, Ui.TEXT_DIM).apply {
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        guide.addView(readiness)
        val gap = View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(10)) }
        guide.addView(gap)
        guide.addView(Ui.btnFull(this@MainActivity, "打开悬浮窗去标记位置", Ui.Kind.SECONDARY) { openOverlay() })
        guide.addView(Ui.btnFull(this@MainActivity, "设置技能与走位间隔", Ui.Kind.SECONDARY) { showPage(1) })
        addView(Ui.card(this@MainActivity, "准备情况", guide))

        addView(Ui.card(this@MainActivity, "走位动作", Ui.text(this@MainActivity,
            "左 D  →  右 2D  →  左 D\n松开轮盘，等待 1 秒，跳一次", 14f).apply {
            setLineSpacing(dp(7).toFloat(), 1f)
        }))

        recent = Ui.text(this@MainActivity, "尚未执行动作", 12f, Ui.TEXT_DIM).apply {
            maxLines = 4
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        addView(Ui.card(this@MainActivity, "最近状态", recent))
    }

    // ------------------------------------------------------------ 设置页

    private fun settings(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(Ui.text(this, "改完点右侧保存生效", 12f, Ui.TEXT_FAINT).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(Ui.btn(this, "保存设置", Ui.Kind.PRIMARY, 14f, 40) { saveSettings() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(40))
        })
        return page(bar) {
            val skillBody = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            skillBody.addView(Ui.doc(this@MainActivity,
                "勾选需要自动补的技能；秒数是该技能的重置周期。未勾选的技能不必标记。"))
            // doc 自带下间距，这里补一行空隙
            skillBody.addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(6))
            })
            Engine.buffConfig().forEach { slot ->
                val check = Ui.check(this@MainActivity, "技能 ${slot.idx + 1}", slot.enabled)
                val edit = number(slot.durSec.toLong())
                skillRows.add(check to edit)
                skillBody.addView(Ui.checkRow(this@MainActivity, check, edit))
            }
            addView(Ui.card(this@MainActivity, "技能间隔", skillBody))

            val walkBody = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            interval = number(WalkFlow.intervalMin)
            leg = number(WalkFlow.legMs)
            push = number(WalkFlow.pushPct.toLong())
            jump = number(WalkFlow.jumpPressMs.toLong())
            walkBody.addView(Ui.labeledRow(this@MainActivity, "执行间隔", interval, "分钟"))
            walkBody.addView(Ui.labeledRow(this@MainActivity, "单程时长 D", leg, "毫秒"))
            walkBody.addView(Ui.labeledRow(this@MainActivity, "推杆幅度", push, "% 屏宽"))
            walkBody.addView(Ui.labeledRow(this@MainActivity, "跳跃按压", jump, "毫秒"))
            walkBody.addView(Ui.doc(this@MainActivity,
                "回位后固定等待 1 秒再跳。推杆幅度越大走得越远，走过头就调小。"))
            addView(Ui.card(this@MainActivity, "原地走位", walkBody))

            val targetBody = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            target = Ui.input(this@MainActivity, OverlayService.targetPkgOf(context), hint = "游戏包名")
            targetBody.addView(target)
            targetBody.addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(8))
            })
            targetBody.addView(Ui.btnFull(this@MainActivity, "用当前前台应用标定", Ui.Kind.SECONDARY) {
                calibrateTarget()
            })
            targetBody.addView(Ui.doc(this@MainActivity,
                "只有目标游戏在前台时才动手；切走会暂停，不会点到桌面上。"))
            addView(Ui.card(this@MainActivity, "目标游戏", targetBody))
        }
    }

    private fun number(value: Long) = Ui.input(this, value.toString(), numeric = true)

    private fun validated(edit: EditText, min: Long, max: Long): Long {
        val value = edit.text.toString().toLongOrNull()
        if (value == null || value !in min..max) {
            edit.error = "请输入 $min–$max"
            edit.requestFocus()
            throw IllegalArgumentException("请检查标红的设置")
        }
        edit.error = null
        return value
    }

    private fun saveSettings() {
        try {
            val seconds = skillRows.map { (_, edit) -> validated(edit, 1, 86_400).toInt() }
            val intervalValue = validated(interval, 1, 1440)
            val legValue = validated(leg, 100, 10_000)
            val pushValue = validated(push, 1, 30).toInt()
            val jumpValue = validated(jump, 30, 600).toInt()
            val pkg = target.text.toString().trim()
            require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(pkg)) {
                "请输入有效游戏包名"
            }
            if (pkg != OverlayService.targetPkgOf(this)) Engine.stop("目标游戏已更改")
            getSharedPreferences("buff", MODE_PRIVATE).edit().apply {
                skillRows.forEachIndexed { i, (check, _) ->
                    putBoolean("enabled$i", check.isChecked); putInt("durSec$i", seconds[i])
                }
            }.apply()
            WalkFlow.intervalMin = intervalValue
            WalkFlow.legMs = legValue
            WalkFlow.pushPct = pushValue
            WalkFlow.jumpPressMs = jumpValue
            OverlayService.setTargetPkgOf(this, pkg)
            LogBus.emitStamped(
                "设置已保存：技能按填写秒数执行；走位每 $intervalValue 分钟，单程 ${legValue}ms"
            )
            toast("设置已保存")
            refresh()
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "参数无效")
        }
    }

    /** 用当前前台应用填目标包名 —— 比让用户手抄包名可靠得多。 */
    private fun calibrateTarget() {
        background("标定目标游戏") {
            val fg = runCatching { ShellCore.probe.foregroundPackage() }.getOrDefault("")
            when {
                fg.isBlank() -> "读取前台应用失败：请确认 Root 可用，或手动填写包名"
                fg == packageName -> "当前前台是本应用，请先切到游戏再点「用当前前台应用标定」"
                else -> {
                    OverlayService.setTargetPkgOf(this, fg)
                    ui.post { target.setText(fg); refresh() }
                    "目标游戏已标定为 $fg"
                }
            }
        }
    }

    // ------------------------------------------------------------ 更多页

    private fun more(): View = page {
        val windowBody = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        windowBody.addView(Ui.btnRow(this@MainActivity,
            Triple("打开悬浮窗", Ui.Kind.SECONDARY) { openOverlay() },
            Triple("关闭悬浮窗", Ui.Kind.SECONDARY) {
                Engine.stop("关闭悬浮窗"); OverlayService.stop(this@MainActivity)
            },
            Triple("检查 Root", Ui.Kind.SECONDARY) {
                background("检查 Root") { ShellCore.probe.requestRoot() }
            }
        ))
        windowBody.addView(Ui.doc(this@MainActivity,
            "标记位置只能在游戏画面的悬浮窗里完成。Root 用于注入触摸与免确认安装。"))
        addView(Ui.card(this@MainActivity, "悬浮窗与权限", windowBody))

        val markBody = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        markBody.addView(Ui.btnRow(this@MainActivity,
            Triple("查看标记", Ui.Kind.SECONDARY) { LogBus.emit(Picks.describe(this@MainActivity)) },
            Triple("清空标记", Ui.Kind.GHOST) { confirmClearPicks() }
        ))
        markBody.addView(Ui.doc(this@MainActivity, "标记按屏幕方向与分辨率保存；换了画面尺寸需要重标。"))
        // 计数每次 refresh 重算，否则标完一圈回到这一页还显示旧数字
        markCount = Ui.text(this@MainActivity, annotationCount(), 12f, Ui.TEXT_FAINT)
        addView(Ui.card(this@MainActivity, "位置标记", markBody, markCount))

        val updateBody = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        updateBody.addView(Ui.btnRow(this@MainActivity,
            Triple("检查并更新", Ui.Kind.SECONDARY) { background("下载并更新") { updater.updateAuto() } },
            Triple("更新日志", Ui.Kind.SECONDARY) { background("读取更新日志") { updater.readUpdateLog() } }
        ))
        sourceUrl = Ui.input(this@MainActivity, updater.savedUrl(), hint = "自定义 HTTPS 地址")
        updateBody.addView(sourceUrl)
        updateBody.addView(View(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(8))
        })
        updateBody.addView(Ui.btnFull(this@MainActivity, "用指定地址或本地文件更新", Ui.Kind.SECONDARY) {
            val value = sourceUrl.text.toString().trim()
            updater.saveUrl(value)
            background("指定来源更新") {
                if (value.startsWith("https://")) updater.updateFromUrl(value, false)
                else updater.installLocal(value, false)
            }
        })
        updateBody.addView(Ui.doc(this@MainActivity,
            "更新只在点击时发生，不会自动下载。安装时应用会被系统杀掉，约 5 秒后自动重启；" +
                "重启后点「更新日志」确认结果。"))
        addView(Ui.card(this@MainActivity, "软件更新", updateBody,
            Ui.text(this@MainActivity, "v${updater.currentVersionName()}", 12f, Ui.TEXT_FAINT)))

        val logBody = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        logBody.addView(Ui.btnRow(this@MainActivity,
            Triple("复制", Ui.Kind.SECONDARY) { copyLog() },
            Triple("导出", Ui.Kind.SECONDARY) { exportLog() },
            Triple("清空", Ui.Kind.GHOST) { LogBus.clear(); logTail = ""; logs.text = "" }
        ))
        logs = Ui.text(this@MainActivity, "", 11f, Ui.TEXT_DIM, mono = true).apply {
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val logHeight = maxOf(dp(120), (resources.displayMetrics.heightPixels * 0.30f).toInt())
        logScroll = ScrollView(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, logHeight)
            background = Ui.shape(context, Ui.LOGBG, Ui.RADIUS_CTRL, Ui.BORDER)
            addView(logs)
        }
        logBody.addView(logScroll)
        addView(Ui.card(this@MainActivity, "运行日志", logBody))
    }

    private fun confirmClearPicks() {
        AlertDialog.Builder(this)
            .setMessage("清空所有位置标记？任务会先停止。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                Engine.stop("清空标记")
                Picks.clearAll(this)
                LogBus.emit("已清空全部位置标记。")
                refresh()
            }
            .show()
    }

    private fun copyLog() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("运行日志", LogBus.dump()))
        toast("日志已复制")
    }

    private fun exportLog() {
        runCatching {
            File(getExternalFilesDir(null) ?: filesDir, "altair_log.txt").apply { writeText(LogBus.dump()) }
        }.onSuccess { toast("已保存：${it.absolutePath}") }
            .onFailure { toast("导出失败：${it.message}") }
    }

    /** 只在用户本来就在看最新一行时才自动跟滚 —— 否则会打断"往上翻历史"。 */
    private fun scrollLogToEnd() {
        val sv = logScroll
        val child = sv.getChildAt(0) ?: return
        if (sv.height <= 0) return
        if (sv.scrollY + sv.height >= child.height - dp(16)) {
            sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ------------------------------------------------------------ 定时刷新

    private fun refresh() {
        val busy = Busy.current
        val armed = OverlayService.isTargetForeground(this)

        if (busy != null) {
            statusDot.setTextColor(Ui.WARN)
            statusText.text = "⏳ $busy"
            metrics.text = "已用 ${Busy.elapsedMs() / 1000} 秒 · 完成后结果写入日志"
        } else {
            statusDot.setTextColor(Ui.statusColor(Engine.isRunning, Engine.state == Engine.State.ERROR, armed))
            statusText.text = Engine.stateText()
            val now = SystemClock.elapsedRealtime()
            metrics.text = if (Engine.isRunning) {
                "补 ${Ui.mmss(Engine.nextBuffDueAt - now, true)} · 走 ${Ui.mmss(Engine.nextWalkDueAt - now, true)}" +
                    " · 技能 ${Engine.buffCastCount} / 走位 ${Engine.walkCount}" +
                    if (Engine.failStreak > 0) " · 连败 ${Engine.failStreak}" else ""
            } else {
                "标记 ${annotationCount()} · 下次执行见启动后的倒计时"
            }
        }

        status.text = Engine.stateText()
        status.setTextColor(
            Ui.statusColor(Engine.isRunning, Engine.state == Engine.State.ERROR, armed)
        )
        val now = SystemClock.elapsedRealtime()
        val skillTime = if (Engine.nextBuffDueAt <= 0) "—" else Ui.mmss(Engine.nextBuffDueAt - now, Engine.isRunning)
        countdown.text = "下次补技能  $skillTime      下次走位  ${Engine.countdown(Engine.nextWalkDueAt)}\n" +
            "已执行  技能 ${Engine.buffCastCount} 次 · 走位 ${Engine.walkCount} 次"
        readiness.text = checklistText()
        markCount.text = annotationCount()

        val active = Engine.isRunning || Actions.busy
        startButton.text = when {
            Engine.isStopping -> "正在停止…"
            active -> "停止任务"
            else -> "启动任务"
        }
        startButton.isEnabled = !Engine.isStopping
        Ui.paint(startButton, if (active) Ui.Kind.DANGER else Ui.Kind.PRIMARY)
    }

    /** 清单：必需项缺了标出来，可选项（跳跃）单独说明。 */
    private fun checklistText(): String = Picks.checklist(this).joinToString("\n") { (slot, need) ->
        val ok = Picks.get(this, slot) != null
        when {
            ok -> "✓ ${Picks.label(slot)}"
            need -> "○ ${Picks.label(slot)}    未标记（必需）"
            else -> "○ ${Picks.label(slot)}    未标记（可选，走位不跳）"
        }
    }

    private fun annotationCount(): String {
        val list = Picks.checklist(this)
        return "${list.count { Picks.get(this, it.first) != null }}/${list.size} 项"
    }

    // ------------------------------------------------------------ 动作

    private fun toggleEngine() {
        if (Engine.isRunning || Actions.busy || Engine.isStopping) Engine.stop() else Engine.start(this)
        refresh()
    }

    private fun openOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
            )
            toast("允许显示悬浮窗后，再点一次「打开悬浮窗」")
            return
        }
        OverlayService.start(this)
        toast("悬浮窗已打开，请切到游戏标记位置")
    }

    /**
     * 跑一个耗时操作。
     *
     * 三段式反馈：底部操作条立刻变成"⏳ 名称 + 已用秒数"（[Busy]），完成后把**带时间戳**的
     * 结果写进日志页。改造前这里只有一句 Toast，用户根本不知道跑完没有。
     */
    private fun background(name: String, work: () -> String) {
        if (Engine.isRunning || Engine.isStopping || Actions.busy || Busy.isBusy) {
            toast("请先停止任务，并等待当前操作完成")
            return
        }
        Busy.begin(name)
        Thread {
            val result = try {
                work()
            } catch (e: Exception) {
                "$name 失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                Busy.end()
            }
            LogBus.emitStamped(result)
            ui.post { refresh() }
        }.apply { isDaemon = true; start() }
    }

    private fun dp(value: Int) = Ui.dp(this, value)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
