package com.altair.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * 悬浮控制台
 * ==========
 *
 * 挂在游戏画面上的控制层，两种形态：
 * ```
 *   ① 悬浮球（默认）：一个圆点，色环 = 状态，中间 = 下次补 BUFF 倒计时。点一下展开。
 *   ② 展开面板     ：启停任务 / 标注坐标 / 工具，收起后回到球。
 * ```
 * 面板上只有四件事：
 * ```
 *   ① 启动 / 停止挂机任务
 *   ② 标注坐标：标技能1..4 / 标跳跃 / 标轮盘（点一下按钮，再点画面上那个位置）
 *   ③ 标点回显：把标好的点画在画面上，确认位置对不对
 *   ④ 试走位一次：手动跑一遍「左 D → 右 2D → 左 D → 跳」
 * ```
 *
 * ## 为什么折叠态是球而不是一条标题栏
 * 目标机是 **1280x720 横屏**。改造前的折叠态是一条标题栏，展开后是"标题 + 3 行状态 +
 * 4 排按钮"，加起来接近半个屏幕高 —— 挂机时面板一直挡着游戏画面。
 * 现在折叠态压成一个 56dp 的球，状态靠色环和倒计时两个字符传递，占地极小；
 * 展开态也把 3 行状态压成 1 行摘要，启停合成一颗整行按钮。
 *
 * ## 为什么标注必须由用户点一下
 * 见 [Picks] 的类注释：自动识别一旦偏了，代价是点到游戏里别的地方，且从日志看不出来。
 * 手动标注一次几秒，永远不会偏。
 *
 * ## 标注与"改一个点"
 * 每个槽位**独立标注、独立保存**（改技能2 不会碰到技能1）—— 这是从"整批重采"换过来的：
 * 整批重采时只想改一个菜单坐标也得把所有点重采一遍，采一半退出还会把已标好的全冲掉。
 * 展开面板上每颗标注按钮自带"已标/未标"状态，缺哪一项一眼可见。
 *
 * ## 前台门禁
 * 所有输入类动作默认先检查"目标游戏是否在前台"（[act] 的 guard 参数）。
 * 焦点一旦跑到桌面、系统弹窗或本应用上，注入的点击就会打到错误的地方。
 */
class OverlayService : Service() {

    companion object {
        private const val CH_ID = "altair_overlay"
        private const val NOTIF_ID = 1001

        /** 展开态面板宽度（dp）。标注按钮要放得下 2 列。 */
        private const val PANEL_W_FULL = 200

        /** 悬浮球直径（dp）。48 是 Android 的最小触摸目标，再加一圈色环的视觉余量。 */
        private const val BALL_D = 56

        /** 判定"这是拖动"的位移阈值（dp）。低于它算点击。 */
        private const val DRAG_SLOP = 8

        /** 标点回显开关的持久化键（放 overlay prefs，和门禁目标同一份）。 */
        private const val KEY_MARKS = "marksOn"

        /** 面板与球的落点记忆 —— 省得每次启动都重新摆一次。 */
        private const val KEY_PANEL_X = "panelX"
        private const val KEY_PANEL_Y = "panelY"
        private const val KEY_BALL_X = "ballX"
        private const val KEY_BALL_Y = "ballY"

        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("overlay", Context.MODE_PRIVATE)

        /** 门禁目标包名。默认值只是占位，实际用「用当前前台标定」定。 */
        fun targetPkgOf(ctx: Context): String =
            prefs(ctx).getString("targetPkg", "com.nexon.mod") ?: "com.nexon.mod"

        fun setTargetPkgOf(ctx: Context, pkg: String) {
            prefs(ctx).edit().putString("targetPkg", pkg).apply()
        }

        fun marksOnOf(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_MARKS, false)

        fun setMarksOnOf(ctx: Context, on: Boolean) {
            prefs(ctx).edit().putBoolean(KEY_MARKS, on).apply()
        }
    }

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var ball: BallView? = null
    private var marksView: MarksView? = null
    private var pickView: PickView? = null

    private var panelParams: WindowManager.LayoutParams? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var panelScroll: ScrollView? = null

    private var statusPill: TextView? = null
    private var statusSummary: TextView? = null
    private var mainBtn: TextView? = null
    private var annoHead: TextView? = null
    private var annoCount: TextView? = null
    private var cancelPickBtn: LinearLayout? = null
    private var marksBtn: TextView? = null

    /** 标注按钮：槽位 → 按钮。用来刷新"已标/未标"的状态前缀。 */
    private val slotBtns = LinkedHashMap<String, TextView>()

    /** 正在标注的槽位（null = 没在标注）。 */
    private var pickSlot: String? = null

    /**
     * 连续标注的待标队列。
     *
     * 空 = 单点标注（标完一项就回面板）；非空 = 连续模式，标完一项自动弹出下一项的采点层。
     * 用户点「连续标技能 1→4」时填 [Picks.SKILLS]，所以顺序天然就是 1→2→3→4。
     */
    private val pickQueue = ArrayDeque<String>()

    private var targetPkg: String = "com.nexon.mod"
    private var lastFg: String = "?"

    private val ui = Handler(Looper.getMainLooper())

    /** 悬浮窗不显示日志（日志看主界面的日志页），这里只做启动提示，避免无谓刷新。 */
    private val logListener: (String) -> Unit = { /* no-op */ }

    private fun prefs() = getSharedPreferences("overlay", MODE_PRIVATE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ShellCore.init(this)
        targetPkg = targetPkgOf(this)
        Engine.init(this)
        WalkFlow.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        LogBus.add(logListener)
        showBall()
        running = true
        LogBus.emit("悬浮控制台已启动。点悬浮球展开控制台 → 标注坐标 → 启动任务。")
        Thread {
            ShellCore.ensureRoot()
            ui.post { refreshStatus(true) }
        }.apply { isDaemon = true }.start()
        // 两条循环错开起跑：500ms 后开始跳秒（纯本地计算），1.2s 后开始查前台（要起 shell）
        ui.postDelayed(tickLoop, 500)
        ui.postDelayed(pollLoop, 1200)
    }

    override fun onDestroy() {
        running = false
        ui.removeCallbacks(tickLoop)
        ui.removeCallbacks(pollLoop)
        LogBus.remove(logListener)
        panel?.let { runCatching { wm.removeView(it) } }
        ball?.let { runCatching { wm.removeView(it) } }
        marksView?.let { runCatching { wm.removeView(it) } }
        pickView?.let { runCatching { wm.removeView(it) } }
        panel = null
        ball = null
        marksView = null
        pickView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 状态轮询

    /** 上一次跑"重查询"（前台包名）的时间。 */
    private var lastHeavyRefresh = 0L

    /** 快速定时：只更新会随时间变化的倒计时。250ms 让秒数跳得干脆。 */
    private val tickLoop = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { tickCountdowns() }
            ui.postDelayed(this, 250)
        }
    }

    /**
     * 慢速定时：查前台包名 + 同步回显层。
     *
     * 前台包名查询要**跨 root shell 起一次进程**（几毫秒到几十毫秒），绝不能按 250ms 跑 ——
     * 那会把常驻 shell 占满，和按键注入抢通道（历史上正是这么把按键堵成 6 秒一次的）。
     * 2 秒一次足够：门禁只需要在动作发生的那一刻准确，而那一刻 [act] 会自己再查一次。
     */
    private val pollLoop = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { refreshHeavy() }
            runCatching { syncMarks() }
            ui.postDelayed(this, 2000)
        }
    }

    /** 只重画倒计时，不碰任何会阻塞的东西。 */
    private fun tickCountdowns() {
        val now = System.currentTimeMillis()
        ball?.let { b ->
            b.remainMs = Engine.nextBuffDueAt - now
            if (b.visibility == View.VISIBLE) b.invalidate()
        }
        // 面板开着时摘要那行也要跟着跳
        if (panel != null && Engine.isRunning) {
            val s = buildStatus()
            if (statusSummary?.text != s) statusSummary?.text = s
        }
    }

    /** 重查询：前台包名 + 引擎状态 + 标注徽章。 */
    private fun refreshHeavy() {
        val fg = runCatching { ShellCore.probe.foregroundPackage() }.getOrDefault("")
        lastFg = fg
        refreshStatus(true)
    }

    /**
     * 刷新状态显示。
     *
     * 所有写入都做了"值变了才 set"，因为 Android 的 `setText` 即使内容一样也会触发
     * requestLayout —— 在 250ms 的节奏下那会让整个窗口一直在重新测量。
     */
    private fun refreshStatus(@Suppress("UNUSED_PARAMETER") force: Boolean = true) {
        val short = shortStatus()
        if (statusPill?.text != short) statusPill?.text = short
        statusPill?.setTextColor(pillColor())

        if (panel != null) {
            val s = buildStatus()
            if (statusSummary?.text != s) statusSummary?.text = s
        }

        // 主按钮跟着引擎状态换文案：跑着的时候必须一眼看出"再点就是停"。
        mainBtn?.let { b ->
            val label = if (Engine.isRunning) "⏹  停止任务" else "▶  启动任务"
            if (b.text != label) b.text = label
        }

        ball?.let { b ->
            b.running = Engine.isRunning
            b.error = Engine.state == Engine.State.ERROR
            b.armed = lastFg == targetPkg
            b.remainMs = Engine.nextBuffDueAt - System.currentTimeMillis()
            if (b.visibility == View.VISIBLE) b.invalidate()
        }

        refreshAnnoBadges()
    }

    /** 面板摘要：一行，替代改造前的 3 行 statusTv。 */
    private fun buildStatus(): String {
        val armed = lastFg == targetPkg
        return if (Engine.isRunning) {
            "补 " + Ui.mmss(Engine.nextBuffDueAt - System.currentTimeMillis(), true) +
                " · 走 " + Ui.mmss(Engine.nextWalkDueAt - System.currentTimeMillis(), true) +
                " · 补${Engine.buffCastCount}/走${Engine.walkCount}" +
                if (Engine.failStreak > 0) " · 连败${Engine.failStreak}" else ""
        } else {
            "已停止 · 标注 " + annotationProgress() + (if (armed) "" else " · 非目标游戏")
        }
    }

    /** 悬浮球下的短状态字。 */
    private fun shortStatus(): String = when {
        !Engine.isRunning -> "已停止"
        Engine.state == Engine.State.ERROR -> "⛔ 熔断"
        Engine.state == Engine.State.CASTING -> "▶ 执行中"
        lastFg == targetPkg -> "▶ 运行中"
        else -> "⏸ 游戏不在前台"
    }

    private fun pillColor(): Int = Ui.statusColor(
        running = Engine.isRunning,
        error = Engine.state == Engine.State.ERROR,
        armed = lastFg == targetPkg
    )

    private fun annotationProgress(): String =
        "${Picks.ALL.size - Picks.missing(this).size}/${Picks.ALL.size} 项"

    /** 刷新每颗标注按钮的"已标/未标"前缀。这是改造后新增的：按钮自己带状态。 */
    private fun refreshAnnoBadges() {
        if (slotBtns.isEmpty()) return
        slotBtns.forEach { (slot, btn) ->
            val on = Picks.get(this, slot) != null
            val want = (if (on) "✓ " else "○ ") + Picks.label(slot)
            if (btn.text != want) btn.text = want
            btn.setTextColor(if (on) Ui.TEXT else Ui.TEXT_FAINT)
            btn.background = Ui.ripple(
                this,
                Ui.shape(this, if (on) Ui.SURFACE_2 else Ui.BG, Ui.RADIUS_CTRL, if (on) Ui.PRIMARY else Ui.BORDER),
                Ui.RADIUS_CTRL
            )
        }
        annoCount?.let { tv ->
            val done = Picks.ALL.size - Picks.missing(this).size
            val want = "$done/${Picks.ALL.size} 已标"
            if (tv.text != want) tv.text = want
            tv.setTextColor(if (done == Picks.ALL.size) Ui.OK else Ui.TEXT_FAINT)
        }
        marksBtn?.let { b ->
            val want = marksLabel()
            if (b.text != want) b.text = want
        }
        // 只在采点进行中才显示「取消标注」—— 平时它只会误导。
        cancelPickBtn?.visibility = if (pickSlot != null) View.VISIBLE else View.GONE
    }

    /** 短暂提示：把状态条临时替换成一条消息，2.5 秒后回到正常状态。 */
    private fun flashStatus(msg: String) {
        statusSummary?.text = msg.take(48)
        ui.postDelayed({ if (running) refreshStatus(true) }, 2500)
    }

    // ------------------------------------------------------------ 窗口基础

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Int) = Ui.dp(this, v)

    /** 屏幕可用区域，给拖动越界兜底用。 */
    private fun screenSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    /** 把窗口位置夹回屏幕内 —— 否则球被拖出去就再也找不回来了。 */
    private fun clampPos(p: WindowManager.LayoutParams, w: Int, h: Int) {
        val (sw, sh) = screenSize()
        p.x = p.x.coerceIn(0, (sw - w).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (sh - h).coerceAtLeast(0))
    }

    /**
     * 挂上"拖动 + 点击"监听：整条 view 可拖，位置落盘；没拖动就是点击，调 [onTap]。
     *
     * ## 为什么点击必须在这里自己处理，而不是用 `setOnClickListener`
     * `setOnTouchListener` 只在 `View.onTouchEvent()` **之前**被调用，而 `performClick()`
     * 是在 `onTouchEvent` 的 ACTION_UP 分支里触发的。
     *
     * 这里 `ACTION_DOWN` 必须返回 `true`（不然后续事件收不到），可一旦返回 true，
     * 事件就被标记为已消费，`View.onTouchEvent()` 从此不再被调用 ——
     * 挂在同一个 View 上的 `setOnClickListener` **永远不会触发**。
     * 改造后第一次实测就是这个现象：拖得动，但点不开。
     *
     * 所以点击由这里显式调用 [View.performClick] 来触发（它同时也照顾了无障碍事件）。
     */
    private fun attachDrag(
        v: View,
        p: WindowManager.LayoutParams,
        keyX: String,
        keyY: String,
        onTap: () -> Unit
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (kotlin.math.abs(dx) > dp(DRAG_SLOP) ||
                                kotlin.math.abs(dy) > dp(DRAG_SLOP))) moved = true
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        clampPos(p, view.width, view.height)
                        runCatching { wm.updateViewLayout(view, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // 拖动结束：落盘位置，不触发点击
                        prefs().edit().putInt(keyX, p.x).putInt(keyY, p.y).apply()
                    } else {
                        // 没移动 = 点击。直接用 onTap 而不是 view.performClick()：
                        // 这里已经是"消费事件"的分支，performClick 的返回值不重要，
                        // 而 onTap 少一层间接、意图更直白。
                        onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) prefs().edit().putInt(keyX, p.x).putInt(keyY, p.y).apply()
                    true
                }
                else -> false
            }
        }
    }

    // ------------------------------------------------------------ 悬浮球

    /**
     * 折叠态：一个球。色环表示状态，中间是下次补 BUFF 的倒计时。
     *
     * 点一下展开面板，拖动换位置 —— 两者共用一套触摸处理（见 [attachDrag] 的 moved 判定）。
     */
    private fun showBall() {
        if (ball != null) return
        removePanel()

        val v = BallView(this)
        val p = WindowManager.LayoutParams(
            dp(BALL_D), dp(BALL_D),
            overlayType(),
            // ★ 必须 NOT_FOCUSABLE：否则抢走游戏输入焦点，按键/触摸全部失效
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 默认落在右上角：游戏的血条/技能键都在下方和左侧，右上角最不容易挡住操作
            x = prefs().getInt(KEY_BALL_X, -1)
            y = prefs().getInt(KEY_BALL_Y, dp(24))
        }
        if (p.x < 0) p.x = (screenSize().first - dp(BALL_D) - dp(12)).coerceAtLeast(0)

        // 拖动之外的抬手 = 点击 → 展开面板
        attachDrag(v, p, KEY_BALL_X, KEY_BALL_Y) { showPanel() }

        v.running = Engine.isRunning
        v.error = Engine.state == Engine.State.ERROR
        v.armed = lastFg == targetPkg
        v.remainMs = Engine.nextBuffDueAt - System.currentTimeMillis()

        runCatching { wm.addView(v, p) }
            .onSuccess {
                ball = v
                ballParams = p
                v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    clampPos(p, v.width, v.height)
                    runCatching { wm.updateViewLayout(v, p) }
                }
            }
            .onFailure { LogBus.emit("悬浮球添加失败: ${it.message}（多半是没有悬浮窗权限）") }
    }

    private fun removeBall() {
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
        ballParams = null
    }

    // ------------------------------------------------------------ 展开面板

    private fun showPanel() {
        if (panel != null) return
        removeBall()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.shape(this@OverlayService, Ui.SURFACE, Ui.RADIUS_CARD, Ui.BORDER)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        // ---------------- 标题栏（整条可拖动 + 点击收起）----------------
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusPill = TextView(this).apply {
            text = "…"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT_DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 收起按钮**刻意不设 OnClickListener**：整条标题栏才是"点一下收起、按住拖动"的热区。
        // 若让这个子 View 可点，它会把 ACTION_DOWN 独吞掉，在它身上起手就拖不动了。
        val collapseBtn = TextView(this).apply {
            text = "▾"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Ui.TEXT_DIM)
            setPadding(dp(10), dp(4), dp(4), dp(4))
        }
        titleBar.addView(statusPill)
        titleBar.addView(collapseBtn)
        root.addView(titleBar)

        // ---------------- 内容 ----------------
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        // ① 单行摘要（改造前这里是 3 行，是面板占高的主因）
        statusSummary = TextView(this).apply {
            text = "…"
            setTextColor(Ui.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), 0, dp(2), dp(6))
        }
        content.addView(statusSummary)

        // ② 启停：合成一颗整行按钮（原来两颗并排，容易误点）
        mainBtn = overlayBtn(if (Engine.isRunning) "⏹  停止任务" else "▶  启动任务",
            fill = if (Engine.isRunning) Ui.DANGER else Ui.OK, heightDp = 44) {
            if (Engine.isRunning) stopEngine() else startEngine()
        }
        content.addView(mainBtn)
        content.addView(Ui.divider(this))

        // ③ 标注区（可折叠，默认展开）—— 每颗按钮自带"已标/未标"状态
        val (annoHeader, annoBody) = collapsibleHeader("标注", open = true)
        annoHead = annoHeader
        annoCount = TextView(this).apply {
            text = "0/6 已标"
            setTextColor(Ui.TEXT_FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
        }
        content.addView(headerRow(annoHeader, annoCount))
        content.addView(annoBody)

        // ---- 标注按钮：一行 3 键的网格（紧凑）----
        // 原来技能 4 个挤一行 + 跳跃轮盘各占整行 = 3 行高度，且 4 个按钮每个只有 ~45dp 宽。
        // 改成一行 3 个、6 项排满 2 行：更省纵向空间，单个按钮也更宽好点。
        slotBtns.clear()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Picks.ALL.chunked(3).forEach { rowSlots ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowSlots.forEach { slot ->
                val b = overlayBtn(("○ ") + Picks.label(slot), fill = Ui.BG, border = Ui.BORDER,
                    heightDp = 30, compact = true) { startSlotPick(slot) }
                // ★ 长按 = 从这一颗起连续标注（技能3 → 3、4）
                b.setOnLongClickListener {
                    if (slot in Picks.SKILLS) {
                        startSkillSequence(slot)
                        true
                    } else {
                        LogBus.emit("连续标注只对「技能1..4」有效")
                        false
                    }
                }
                (b.layoutParams as LinearLayout.LayoutParams).apply {
                    marginEnd = dp(2)
                    bottomMargin = dp(2)
                }
                slotBtns[slot] = b
                row.addView(b)
            }
            // 补齐空位，保证最后一行按钮宽度与上一行对齐
            repeat(3 - rowSlots.size) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(2) }
                })
            }
            grid.addView(row)
        }
        annoBody.addView(grid)

        // 「连续标技能」主入口：面板上直接可见，不用去摸长按
        annoBody.addView(overlayBtn("▶ 连续标技能 1→4", fill = Ui.SURFACE_2, border = Ui.PRIMARY,
            heightDp = 30, compact = true) { startSkillSequence(Picks.SKILL1) })

        cancelPickBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(overlayBtn("✕ 取消标注", fill = Ui.SURFACE_2, border = Ui.BORDER,
                heightDp = 30, compact = true) { cancelSlotPick() })
        }
        annoBody.addView(cancelPickBtn)

        // ④ 工具区（可折叠，默认收起）
        val (toolHeader, toolBody) = collapsibleHeader("工具", open = false)
        content.addView(toolHeader)
        content.addView(toolBody)

        marksBtn = overlayBtn(marksLabel(), fill = Ui.SURFACE_2, border = Ui.BORDER) { toggleMarks() }
        toolBody.addView(marksBtn)
        toolBody.addView(overlayBtn("试走位一次", fill = Ui.SURFACE_2, border = Ui.BORDER) { testStroll() })
        toolBody.addView(overlayBtn("复位窗口位置", fill = Ui.SURFACE_2, border = Ui.BORDER) { resetPositions() })
        toolBody.addView(overlayBtn("清空标注", fill = Ui.SURFACE_2, border = Ui.DANGER) { clearAllPicks() })

        val scroll = ScrollView(this).apply {
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = true
        }
        panelScroll = scroll
        root.addView(scroll)

        // ---------------- 窗口参数 ----------------
        val p = WindowManager.LayoutParams(
            dp(PANEL_W_FULL),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs().getInt(KEY_PANEL_X, dp(10))
            y = prefs().getInt(KEY_PANEL_Y, dp(10))
        }

        // 标题条上除了拖动，抬手还要收起面板（拖动时不触发 —— 见 attachDrag）
        attachDrag(titleBar, p, KEY_PANEL_X, KEY_PANEL_Y) { showBall() }

        panel = root
        panelParams = p
        runCatching { wm.addView(root, p) }
            .onSuccess {
                root.post { capPanelHeight() }
                refreshStatus(true)
            }
            .onFailure { LogBus.emit("悬浮窗添加失败: ${it.message}（多半是没有悬浮窗权限）") }
    }

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelParams = null
        panelScroll = null
        // 这些是面板里的控件，跟着面板一起消失 —— 留着引用会指向已 detach 的 View
        statusPill = null
        statusSummary = null
        mainBtn = null
        annoHead = null
        annoCount = null
        marksBtn = null
        cancelPickBtn = null
        slotBtns.clear()
    }

    private fun marksLabel() = if (marksOnOf(this)) "回显:开" else "回显:关"

    /**
     * 高度兜底：横屏可用高度只有 720px，展开后的面板很容易顶出屏幕。
     * 把总高限制在屏幕 88% 以内，超出部分交给 ScrollView 内部滚动。
     */
    private fun capPanelHeight() {
        val root = panel ?: return
        val scroll = panelScroll ?: return
        val maxTotal = (resources.displayMetrics.heightPixels * 0.88f).toInt()
        if (root.height > maxTotal) {
            val titleH = (statusPill?.height ?: dp(20))
            val limit = (maxTotal - titleH - dp(12)).coerceAtLeast(dp(60))
            scroll.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, limit
            )
            scroll.requestLayout()
        }
    }

    /** 把所有窗口位置重置回默认（右上角球 / 左上角面板）。 */
    private fun resetPositions() {
        prefs().edit()
            .remove(KEY_BALL_X).remove(KEY_BALL_Y)
            .remove(KEY_PANEL_X).remove(KEY_PANEL_Y).apply()
        val ok = ball != null || panel != null
        if (ball != null) { showBall() } else if (panel != null) { removePanel(); showBall() }
        LogBus.emit(if (ok) "窗口位置已复位。点悬浮球重新展开。" else "窗口位置已复位。")
        flashStatus("已复位窗口位置")
    }

    // ------------------------------------------------------------ 面板控件工厂

    /**
     * 面板里的按钮。
     *
     * 面板用的是 dp 而不是 sp —— 覆盖层里的 sp 跟随系统字体缩放，目标机 720p 横屏下
     * 系统字号一大就撑破框。主界面是普通 Activity，那边用 sp 没问题。
     */
    private fun overlayBtn(
        label: String,
        fill: Int,
        border: Int = 0,
        heightDp: Int = 28,
        compact: Boolean = false,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 9f else 10f)
        setTextColor(Ui.TEXT)
        setPadding(dp(2), 0, dp(2), 0)
        background = Ui.ripple(this@OverlayService, Ui.shape(
            this@OverlayService, fill, Ui.RADIUS_CTRL, border), Ui.RADIUS_CTRL)
        isClickable = true
        if (compact) {
            // 网格里由父容器赋权重平分宽度
            layoutParams = LinearLayout.LayoutParams(0, dp(heightDp), 1f)
        } else {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
            ).apply { bottomMargin = dp(3) }
        }
        setOnClickListener { onClick() }
    }

    /** "▾ 标题 ……… 右侧计数"的一行。 */
    private fun headerRow(head: TextView, right: TextView?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(head, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (right != null) addView(right)
        }

    /**
     * 可折叠小节的标题条。返回标题（点击切换）+ 内容容器。
     *
     * 面板空间紧张，标注和工具两组默认状态不同：标注是主功能（常开），工具是偶尔用（默认收起）。
     */
    private fun collapsibleHeader(title: String, open: Boolean): Pair<TextView, LinearLayout> {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (open) View.VISIBLE else View.GONE
        }
        val head = TextView(this).apply {
            text = (if (open) "▾ " else "▸ ") + title
            setTextColor(Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(6))
            isClickable = true
            setOnClickListener {
                val show = body.visibility != View.VISIBLE
                body.visibility = if (show) View.VISIBLE else View.GONE
                text = (if (show) "▾ " else "▸ ") + title
                panel?.post { capPanelHeight() }
            }
        }
        return head to body
    }

    // ------------------------------------------------------------ 动作

    /**
     * 执行一个动作。
     *
     * [guard] 为 true 时先做**前台门禁**：只有目标游戏在前台才执行。
     * 这是防止「在错误界面上乱点」的关键。
     */
    private fun act(label: String, guard: Boolean = true, block: () -> String) {
        LogBus.emit("▸ $label")
        Thread {
            if (guard) {
                val fg = runCatching { ShellCore.probe.foregroundPackage() }.getOrDefault("")
                if (fg != targetPkg) {
                    LogBus.emit(
                        "   ⛔ 已跳过：当前前台是「${fg.ifBlank { "未知" }}」，" +
                            "不是目标游戏「$targetPkg」（用「用当前前台标定」可改目标）"
                    )
                    flashStatus("⛔ 游戏不在前台")
                    return@Thread
                }
            }
            val r = runCatching { block() }
                .getOrElse { "出错: ${it.javaClass.simpleName}: ${it.message}" }
            LogBus.emit(r.trimEnd())
            ui.post { refreshStatus(true) }
        }.apply { isDaemon = true }.start()
    }

    /** 启动挂机引擎（等于主界面的启动，省得来回切 App）。 */
    private fun startEngine() {
        runCatching { Engine.start(this) }
        // 引擎启动失败是**设状态**而不是抛异常（比如"没标注技能图标"），所以看状态
        val text = if (Engine.isRunning) {
            "✅ 任务已启动"
        } else {
            "❌ 启动失败：" + Engine.lastError.ifBlank { "原因见日志" }
        }
        LogBus.emit(text)
        flashStatus(text)
        ui.post { refreshStatus(true) }
    }

    private fun stopEngine() {
        Engine.stop("悬浮窗停止")
        val msg = "⏹ 任务已停止"
        LogBus.emit(msg)
        flashStatus(msg)
        ui.post { refreshStatus(true) }
    }

    /** 手动试一次走位（不补 BUFF，纯验证走位闭环）。 */
    private fun testStroll() {
        Thread {
            LogBus.emit("▸ 试走位一次")
            LogBus.emit("   " + WalkFlow.describe(this).replace("\n", "；"))
            val (ok, msg) = WalkFlow.strollAndJump { LogBus.emit(it) }
            LogBus.emit(if (ok) "   ✅ $msg" else "   ⚠ $msg")
            flashStatus(if (ok) "✅ 走位完成" else "⚠ 走位有问题")
            ui.post { refreshStatus(true) }
        }.apply { isDaemon = true }.start()
    }

    private fun clearAllPicks() {
        Picks.clearAll(this)
        LogBus.emit("已清空全部标注（技能1-4 / 跳跃 / 轮盘）。")
        flashStatus("已清空标注")
        marksView?.let { runCatching { wm.removeView(it) } }
        marksView = null
        ui.post { refreshStatus(true) }
    }

    // ------------------------------------------------------------ 单点标注

    /**
     * 标注一个槽位：铺一层全屏透明可触摸层，用户点画面上那个位置，采到就保存。
     *
     * 采点期间游戏收不到点击（正好，标注不该有副作用）。
     *
     * ## 面板去哪了
     * 采点层是全屏可触摸的，会盖住面板。所以先**把面板收成球**再加采点层 ——
     * 免得用户被一层看不见的东西挡住、又没法回去点面板取消。
     * 采点层自带「完成」按钮，面板上的「取消标注」也能中断，两条退路。
     *
     * ## 顺序很重要
     * ★ 必须先摘下面板再加采点层，否则采点层盖住面板，用户便无法点「取消标注」。
     */
    private fun startSlotPick(slot: String) {
        if (pickView != null) finishSlotPick()
        val v = PickView(this, "点一下「${Picks.label(slot)}」在画面上的位置")
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // 可触摸（要接收点击）但不吃焦点（否则会抢游戏输入焦点）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        v.onFinish = { ui.post { finishSlotPick() } }

        removePanel()                       // ★ 顺序见上
        runCatching { wm.addView(v, p) }
            .onSuccess {
                pickView = v
                pickSlot = slot

                // 连续模式下把进度说清楚，用户才知道还剩几项、点完会不会自动接上下一项
                val msg = if (pickQueue.isNotEmpty()) {
                    "连续标注 ${Picks.SKILLS.indexOf(slot) + 1}/${Picks.SKILLS.size}：" +
                        "点画面上「${Picks.label(slot)}」的位置（点完自动接下一项）"
                } else {
                    "标注「${Picks.label(slot)}」：点画面上那个位置，再点下方「完成」"
                }
                LogBus.emit(msg)
            }
            .onFailure {
                pickView = null
                pickSlot = null
                pickQueue.clear()
                LogBus.emit("标注层添加失败：${it.message}")
                ui.post { showPanel() }
            }
    }

    /**
     * 连续标注：从 [from] 开始，按 [Picks.SKILLS] 的顺序一路标下去（技能1→2→3→4）。
     *
     * 队列填好后立刻开始第一项；之后每标完一项，[finishSlotPick] 会自动弹下一项，
     * 用户只要在游戏画面上连点四个技能图标就行，不用来回切面板。
     *
     * 为了省一次点击，「连续标技能」默认从技能1 开始；从技能3 起标就长按那颗按钮。
     */
    private fun startSkillSequence(from: String) {
        val start = Picks.SKILLS.indexOf(from)
        if (start < 0) return
        pickQueue.clear()
        Picks.SKILLS.drop(start).forEach { pickQueue.addLast(it) }
        LogBus.emit("连续标注开始：${Picks.SKILLS.drop(start).joinToString(" → ") { Picks.label(it) }}")
        startSlotPick(pickQueue.removeFirst())
    }

    /** 用户主动取消标注（面板上的按钮）。采到一半也能退，连续模式下会中止整个队列。 */
    private fun cancelSlotPick() {
        val v = pickView ?: return
        val slot = pickSlot          // 先取出来，下面会被清空
        runCatching { wm.removeView(v) }
        pickView = null
        pickSlot = null
        val wasSequence = pickQueue.isNotEmpty()
        pickQueue.clear()
        LogBus.emit(
            "已取消标注，「${slot?.let { Picks.label(it) } ?: "该项"}」未改动" +
                if (wasSequence) "；连续标注已中止" else ""
        )
        flashStatus("已取消标注")
        showPanel()
    }

    /**
     * 结束标注的收尾（三条退出路径共用）。
     *
     * ## 连续模式
     * 队列里还有下一项 → **直接弹出下一项的采点层**，用户不用回面板。
     * 这是"连续标 1/2/3/4"的实现：队列在 [startSkillSequence] 里填好，
     * 每标完一项这里自动推进，直到队列空。
     *
     * ## 单点模式
     * 队列为空 → 回到展开面板（用户下一步八成是标别的槽位，回球上还得再点一次）。
     */
    private fun finishSlotPick() {
        val v = pickView ?: return
        val slot = pickSlot
        runCatching { wm.removeView(v) }
        pickView = null
        pickSlot = null

        val p = v.points.lastOrNull()
        if (slot == null) {
            // 理论上不会走到这（标注一定会带槽位），兜底只记日志
            LogBus.emit("标注结束：没有目标槽位，已丢弃")
            pickQueue.clear()
        } else if (p == null) {
            // 没采到就直接中断连续模式 —— 否则会一路空弹下去，用户不知道发生了什么事
            LogBus.emit("⚠ 没采到点，「${Picks.label(slot)}」未改动；连续标注已中止")
            pickQueue.clear()
        } else {
            Picks.set(this, slot, p.first, p.second)
            LogBus.emit(String.format(
                Locale.US, "✅ %s 已保存 = [%.4f, %.4f]",
                Picks.label(slot), p.first, p.second
            ))
            if (pickQueue.isEmpty()) LogBus.emit("   全部标注：\n" + Picks.describe(this))
        }

        // 连续模式：还有下一项就接着标，不回面板
        val next = pickQueue.removeFirstOrNull()
        if (next != null) {
            ui.post { startSlotPick(next) }
            return
        }
        showPanel()      // 采完回到展开面板
    }

    // ------------------------------------------------------------ 标点回显

    private fun toggleMarks() {
        val on = !marksOnOf(this)
        setMarksOnOf(this, on)
        marksBtn?.text = marksLabel()
        LogBus.emit(if (on) "标点回显：已开启（只在目标游戏前台时显示）" else "标点回显：已关闭")
        if (!on) removeMarks() else syncMarks()
        ui.post { refreshStatus(true) }
    }

    /** 回显只在「开关打开 + 目标游戏在前台」时显示 —— 免得在桌面上也糊一层。 */
    private fun syncMarks() {
        val shouldShow = marksOnOf(this) && lastFg == targetPkg
        if (shouldShow && marksView == null) addMarks()
        else if (!shouldShow && marksView != null) removeMarks()
        if (marksView != null) refreshMarks()
    }

    private fun refreshMarks() {
        val v = marksView ?: return
        v.setData(marksData())
        v.setJoystick(Picks.get(this, Picks.JOYSTICK), WalkFlow.pushPct)
        v.invalidate()
    }

    private fun marksData(): List<Triple<String, Float, Float>> =
        Picks.ALL.mapNotNull { slot ->
            Picks.get(this, slot)?.let { Triple(Picks.label(slot), it.first, it.second) }
        }

    private fun removeMarks() {
        marksView?.let { runCatching { wm.removeView(it) } }
        marksView = null
    }

    private fun addMarks() {
        if (marksView != null) return
        val v = MarksView(this)
        v.setData(marksData())
        v.setJoystick(Picks.get(this, Picks.JOYSTICK), WalkFlow.pushPct)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // 完全透传：既不吃触摸也不吃焦点，纯显示
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        runCatching { wm.addView(v, p) }
            .onSuccess { marksView = v }
            .onFailure { LogBus.emit("标点回显层添加失败：${it.message}") }
    }

    // ------------------------------------------------------------ 通知

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CH_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_ID, "悬浮控制台", NotificationManager.IMPORTANCE_MIN).apply {
                        setShowBadge(false)
                    }
                )
            }
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CH_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("阿尔泰挂机 · 悬浮控制台运行中")
            .setContentText("点悬浮球展开控制台")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }
}

/**
 * 悬浮球（折叠态）
 * =================
 *
 * 一个 56dp 的圆：外圈色环表示状态（绿=运行中 / 橙=游戏不在前台 / 红=熔断 / 灰=停止），
 * 中间是下次补 BUFF 的倒计时 `MM:SS`。
 *
 * ## 为什么用自绘而不是摆几个 View
 * 圆环 + 居中文字用 View 拼要三层嵌套，还不如一个 `onDraw` 干净；而且球很小，
 * 每帧能省下的 measure/layout 都值得 —— 它浮在游戏上面，游戏本身已经在吃性能。
 *
 * ## 为什么不用 sp
 * 覆盖层里的 sp 跟随系统字体缩放。球只有 56dp，系统字号调大后 `04:12` 会溢出圆外，
 * 所以这里用纯密度换算（`COMPLEX_UNIT_DIP`），尺寸可控。
 */
class BallView(private val ctx: Context) : View(ctx) {

    var running: Boolean = false
    var error: Boolean = false
    var armed: Boolean = false
    var remainMs: Long = 0L

    /** 球的底色：比面板深一点，浮在亮色游戏画面上也看得清。 */
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F21A2029")
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }

    init {
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** 当前状态色。与面板标题条共用同一套映射（[Ui.statusColor]）。 */
    private fun ringColor(): Int = Ui.statusColor(running, error, armed)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val d = ctx.resources.displayMetrics.density
        val ringW = 3.5f * d

        // 底色圆（留出环宽）
        canvas.drawCircle(cx, cy, w / 2f - ringW / 2f, bgPaint)

        // 状态色环
        ringPaint.strokeWidth = ringW
        ringPaint.color = ringColor()
        canvas.drawCircle(cx, cy, w / 2f - ringW / 2f, ringPaint)

        // 中间倒计时。未运行时 Ui.mmss 返回 "--:--"，球上仍显示一个占位符，
        // 让"没在跑"和"跑着但还没算出时间"在视觉上一致（都是不动的字符）。
        textPaint.textSize = 12f * d
        textPaint.color = if (running) Color.WHITE else Ui.TEXT_DIM
        val label = Ui.mmss(remainMs, running)
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, baseline, textPaint)
    }
}

/**
 * 标点回显层
 * ==========
 *
 * 只画**用户手动标注的点**，不画任何自动识别框（用户要求：去掉自动识别区）。
 *
 * 为什么还要留这一层：标注完得能一眼确认"点到底落在哪"。没有它，标注准不准只能靠
 * 实机试一次 —— 而试错的代价是点到游戏里别的地方。
 *
 * 轮盘额外画出中心点与左右推杆落点：推杆幅度是个百分比参数，画出来才知道推多远。
 */
class MarksView(ctx: Context) : View(ctx) {

    private var items: List<Triple<String, Float, Float>> = emptyList()
    private var joystick: Pair<Float, Float>? = null
    private var pushPct: Int = 6

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#22FFFFFF")
    }

    fun setData(v: List<Triple<String, Float, Float>>) {
        items = v
    }

    fun setJoystick(center: Pair<Float, Float>?, pct: Int) {
        joystick = center
        pushPct = pct
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1/10 网格，方便肉眼读归一化坐标
        for (i in 1..9) {
            canvas.drawLine(w * i / 10f, 0f, w * i / 10f, h, guide)
            canvas.drawLine(0f, h * i / 10f, w, h * i / 10f, guide)
        }

        // ---- 轮盘中心 + 左右推杆落点 ----
        joystick?.let { (nx, ny) ->
            val cx = nx * w
            val cy = ny * h
            val off = pushPct / 100f * w
            stroke.color = Color.parseColor("#FF3BD16F")
            stroke.strokeWidth = 4f
            canvas.drawCircle(cx, cy, 30f, stroke)
            canvas.drawLine(cx - 44f, cy, cx + 44f, cy, stroke)
            canvas.drawLine(cx, cy - 44f, cx, cy + 44f, stroke)
            fill.color = Color.parseColor("#FF3BD16F")
            canvas.drawRect(cx + 36f, cy - 50f, cx + 36f + text.measureText("轮盘") + 10f, cy - 24f, fill)
            text.color = Color.BLACK
            canvas.drawText("轮盘", cx + 41f, cy - 32f, text)

            stroke.strokeWidth = 3f
            stroke.color = Color.parseColor("#883BD16F")
            for (dx in listOf(-off, off)) {
                canvas.drawCircle(cx + dx, cy, 18f, stroke)
            }
            canvas.drawLine(cx - off, cy, cx + off, cy, stroke)
        }

        // ---- 标注点 ----
        items.forEach { (label, nx, ny) ->
            val cx = nx * w
            val cy = ny * h
            val c = colorOf(label)
            stroke.color = c
            stroke.strokeWidth = 4f
            canvas.drawCircle(cx, cy, 26f, stroke)
            canvas.drawLine(cx - 38f, cy, cx + 38f, cy, stroke)
            canvas.drawLine(cx, cy - 38f, cx, cy + 38f, stroke)
            fill.color = c
            val tw = text.measureText(label) + 10f
            canvas.drawRect(cx + 30f, cy - 46f, cx + 30f + tw, cy - 20f, fill)
            text.color = Color.BLACK
            canvas.drawText(label, cx + 35f, cy - 28f, text)
        }

        // ---- 尺寸与"还缺什么" ----
        text.color = Color.parseColor("#FF3BC9D1")
        val missing = Picks.ALL.filter { slot -> items.none { it.first == Picks.label(slot) } }
        canvas.drawText(
            "${width}x${height} ${if (width > height) "横屏" else "竖屏"}  已标注 ${items.size}/${Picks.ALL.size}" +
                if (missing.isEmpty()) "（齐了）"
                else "（缺：${missing.joinToString("/") { Picks.label(it) }}）",
            12f, h - 14f, text
        )
    }

    private fun colorOf(label: String): Int = when (label) {
        "技能1", "技能2", "技能3", "技能4" -> Color.parseColor("#FFFFC53D")
        "跳跃" -> Color.parseColor("#FFFF7A3D")
        else -> Color.parseColor("#FF3BC9D1")
    }
}
