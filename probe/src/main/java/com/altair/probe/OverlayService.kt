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
import android.graphics.Rect
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        private const val CH_ID = "altair_controls"
        private const val NOTIF_ID = 1001

        /** 展开态面板宽度（dp）。标注按钮要放得下 3 列。 */
        private const val PANEL_W_FULL = 226

        /**
         * 面板上按钮的统一高度（dp）。
         *
         * 44 是 Android 的推荐触摸目标，但面板浮在游戏画面上，一行 3 列时每格只有 ~70dp 宽、
         * 44dp 高会显得又矮又胖。36 在 720p 横屏上仍然点得中，整块面板却能矮掉近 1/3。
         */
        private const val PANEL_CELL_H = 36

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

        @Volatile var picking = false
            private set

        @Volatile var running = false
            private set

        /**
         * 最近一次查到的前台包名（由 [pollLoop] 每 2 秒更新一次）。
         *
         * 主界面读这个值判断"游戏是否在前台"，**绝不能**在 UI 线程现查 ——
         * 一次查询要起一个 `dumpsys window` 进程，按界面 500ms 的刷新节奏会把
         * 常驻 root 通道占满（历史上正是这样把按键堵成 6 秒一次的）。
         */
        @Volatile private var foregroundPkg: String = ""

        /** 目标游戏此刻是否在前台。覆盖层没在运行时一律 false。 */
        fun isTargetForeground(ctx: Context): Boolean =
            foregroundPkg.isNotEmpty() && foregroundPkg == targetPkgOf(ctx)

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
    private var pickParams: WindowManager.LayoutParams? = null

    /** 走位让路期间被"挪开"的窗口 —— (视图, 参数, 原始 x/y/w/h)，走完照原样放回去。 */
    private val vacated = mutableListOf<Pair<View, IntArray>>()

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
    /** 主按钮当前画的是哪一档配色（null = 还没画过）。用来避免 250ms 一次的无谓重绘。 */
    private var mainBtnRunning: Boolean? = null
    private var polling = false
    private var injecting = false
    private var emergency: View? = null
    private var pickGeneration = 0

    private val ui = Handler(Looper.getMainLooper())

    /** 悬浮窗不显示日志（日志看主界面的日志页），这里只做启动提示，避免无谓刷新。 */
    private val logListener: (String) -> Unit = { /* no-op */ }

    private fun prefs() = getSharedPreferences("overlay", MODE_PRIVATE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") Engine.stop("通知栏停止")
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        ShellCore.init(this)
        targetPkg = targetPkgOf(this)
        Engine.init(this)
        WalkFlow.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        LogBus.add(logListener)
        // 注入输入时让覆盖层让路，否则注入会打在自己面板上（见 [InjectShield]）
        // ★ mode 必须透传：INJECT 只置灰（几十毫秒，用户看不见），WALK 才动几何位置。
        InjectShield.bind { mode, on, l, t, r, b -> injectGate(mode, on, l, t, r, b) }
        showBall()
        running = true
        LogBus.emit(
            "悬浮控制台已启动（v${versionName()} · code ${versionCode()}）。" +
                "点悬浮球展开控制台 → 标注坐标 → 启动任务。"
        )
        Thread {
            runCatching { ShellCore.ensureRoot() }.onFailure { LogBus.emit("Root 检查失败：${it.message}") }
            ui.post { refreshStatus(true) }
        }.apply { isDaemon = true }.start()
        // 两条循环错开起跑：500ms 后开始跳秒（纯本地计算），1.2s 后开始查前台（要起 shell）
        ui.postDelayed(tickLoop, 500)
        ui.postDelayed(pollLoop, 1200)
    }

    override fun onDestroy() {
        Engine.stop("悬浮窗已关闭")
        pickGeneration++
        picking = false
        emergency?.let { runCatching { wm.removeView(it) } }
        emergency = null
        running = false
        foregroundPkg = ""
        InjectShield.bind(null)
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
        pickParams = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 状态轮询

    /** 快速定时：只更新会随时间变化的倒计时。250ms 让秒数跳得干脆。 */
    private val tickLoop = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { tickCountdowns(); refreshStatus(true) }
            ui.postDelayed(this, 250)
        }
    }

    /**
     * 慢速定时：查前台包名 + 同步回显层。
     *
     * 前台包名查询要**跨 root shell 起一次进程**（几毫秒到几十毫秒），绝不能按 250ms 跑 ——
     * 那会把常驻 shell 占满，和按键注入抢通道（历史上正是这么把按键堵成 6 秒一次的）。
     * 空闲时每 2 秒查询一次；执行动作时由 Probe 独立检查。
     */
    private val pollLoop = object : Runnable {
        override fun run() {
            if (!running) return
            if (!polling && !Actions.busy) {
                polling = true
                Thread {
                    val fg = runCatching { ShellCore.probe.foregroundPackage() }.getOrDefault("")
                    ui.post {
                        polling = false
                        if (running) {
                            foregroundPkg = fg
                            targetPkg = targetPkgOf(this@OverlayService)
                            refreshStatus(true)
                            syncMarks()
                            healTouchability()
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
            ui.postDelayed(this, 2000)
        }
    }

    /** 只重画倒计时，不碰任何会阻塞的东西。 */
    private fun tickCountdowns() {
        val now = android.os.SystemClock.elapsedRealtime()
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
        // 只在状态真的翻转时才重绘背景 —— 这段是 250ms 一次的，每次都建两个 Drawable
        // 会让悬浮窗一直在做无谓的分配。
        mainBtn?.let { b ->
            val label = if (Engine.isRunning) "⏹ 停止" else "▶ 启动"
            if (b.text != label) b.text = label
            if (mainBtnRunning != Engine.isRunning) {
                mainBtnRunning = Engine.isRunning
                // 带水波纹重绘 —— 直接赋 shape() 会把按压反馈换掉
                b.setTextColor(if (Engine.isRunning) Color.WHITE else Ui.ON_PRIMARY)
                b.background = Ui.ripple(
                    this,
                    Ui.shape(
                        this,
                        if (Engine.isRunning) Ui.DANGER else Ui.PANEL_CTRL_ON,
                        Ui.RADIUS_CTRL,
                        if (Engine.isRunning) 0 else Ui.PANEL_BORDER
                    ),
                    Ui.RADIUS_CTRL
                )
            }
            b.isEnabled = !Engine.isStopping
        }

        ball?.let { b ->
            b.running = Engine.isRunning
            b.error = Engine.state == Engine.State.ERROR
            b.armed = foregroundPkg == targetPkg
            b.remainMs = Engine.nextBuffDueAt - android.os.SystemClock.elapsedRealtime()
            if (b.visibility == View.VISIBLE) b.invalidate()
        }

        refreshAnnoBadges()
    }

    /**
     * 面板摘要：一行（最多两行）。
     *
     * ★ 失败原因要**留在面板上**，不能只闪一下。
     * 用户报过的现象是"点启动任务立马停止" —— 真实流程是启动校验没过、任务根本没跑起来，
     * 但原因只在摘要里显示 2.5 秒就退回"已停止 · 标注 4/6"，看起来就像按钮坏了。
     * 所以 ERROR 状态下摘要一直显示原因，直到用户下次动手。
     */
    private fun buildStatus(): String {
        val armed = foregroundPkg == targetPkg
        return when {
            Engine.isRunning ->
                "补 " + Ui.mmss(Engine.nextBuffDueAt - android.os.SystemClock.elapsedRealtime(), true) +
                    " · 走 " + Ui.mmss(Engine.nextWalkDueAt - android.os.SystemClock.elapsedRealtime(), true) +
                    " · 补${Engine.buffCastCount}/走${Engine.walkCount}" +
                    if (Engine.failStreak > 0) " · 连败${Engine.failStreak}" else ""

            Engine.state == Engine.State.ERROR && Engine.lastError.isNotBlank() ->
                "⛔ " + Engine.lastError.take(60)

            else ->
                "已停止 · 标注 " + annotationProgress() + (if (armed) "" else " · 非目标游戏")
        }
    }

    /** 悬浮球下的短状态字。 */
    private fun shortStatus(): String = when {
        !Engine.isRunning -> "已停止"
        Engine.state == Engine.State.ERROR -> "⛔ 熔断"
        Engine.state == Engine.State.CASTING -> "▶ 执行中"
        foregroundPkg == targetPkg -> "▶ 运行中"
        else -> "⏸ 游戏不在前台"
    }

    private fun pillColor(): Int = Ui.statusColor(
        running = Engine.isRunning,
        error = Engine.state == Engine.State.ERROR,
        armed = foregroundPkg == targetPkg
    )

    /** "已标 / 全部"的进度。必需项与可选项都在里面，缺哪一项由每颗按钮自己标。 */
    private fun annotationProgress(): String {
        val list = Picks.checklist(this)
        return "${list.count { Picks.get(this, it.first) != null }}/${list.size} 项"
    }

    /** 必需项是否都标齐了 —— 决定启动按钮是否可用。 */
    private fun annotationReady(): Boolean = Picks.required(this).all { Picks.get(this, it) != null }

    /** 刷新每颗标注按钮的"已标/未标"前缀。按钮自己带状态，缺哪一项一眼可见。 */
    private fun refreshAnnoBadges() {
        if (slotBtns.isEmpty()) return
        slotBtns.forEach { (slot, btn) ->
            val on = Picks.get(this, slot) != null
            val need = slot in Picks.required(this)
            // 可选项（跳跃）未标时用「◌ 」而不是「○ 」，避免用户以为少标了就启动不了
            val mark = if (on) "✓ " else if (need) "○ " else "◌ "
            val want = mark + Picks.label(slot)
            if (btn.text != want) btn.text = want
            btn.setTextColor(if (on) Ui.PANEL_TEXT else Ui.PANEL_TEXT_DIM)
            btn.background = Ui.ripple(
                this,
                Ui.shape(
                    this,
                    if (on) Ui.PANEL_CTRL_ON else Ui.PANEL_CTRL,
                    Ui.RADIUS_CTRL,
                    if (on) Ui.PANEL_BORDER else 0
                ),
                Ui.RADIUS_CTRL
            )
        }
        annoCount?.let { tv ->
            val list = Picks.checklist(this)
            val done = list.count { Picks.get(this, it.first) != null }
            val want = "$done/${list.size} 已标"
            if (tv.text != want) tv.text = want
            tv.setTextColor(if (annotationReady()) Ui.OK else Ui.PANEL_TEXT_DIM)
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
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post { flashStatus(msg) }; return }
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

    // ------------------------------------------------------------ 注入让路

    /** 当前所有"可触摸"的自有窗口（回显层本来就是 NOT_TOUCHABLE，不算）。 */
    private fun overlayWindows(): List<Pair<View, WindowManager.LayoutParams>> = listOfNotNull(
        panel?.let { v -> panelParams?.let { v to it } },
        ball?.let { v -> ballParams?.let { v to it } },
        pickView?.let { v -> pickParams?.let { v to it } }
    )

    /**
     * 在 UI 线程**同步**执行并等它做完。
     *
     * 窗口参数（位置 / flags）只能在 UI 线程改：`updateViewLayout` 最终会走 ViewRootImpl
     * 的遍历调度，在没有 Looper 的注入线程上调会抛异常。但闸门必须在**注入命令发出之前**
     * 落地，所以 post 过去之后用闩等回来。正常情况下是毫秒级；超时只是兜底。
     */
    private fun onUiSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val done = CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val job = Runnable {
            try { block() } catch (t: Throwable) { error.set(t) } finally { done.countDown() }
        }
        ui.post(job)
        // 清理阶段也必须等待 UI 恢复，不因 worker 中断留下不可触摸的窗口。
        val interrupted = Thread.interrupted()
        try {
            if (!done.await(2000, TimeUnit.MILLISECONDS)) {
                ui.removeCallbacks(job)
                throw IllegalStateException("悬浮窗响应超时，未发送触摸")
            }
            error.get()?.let { throw IllegalStateException("悬浮窗操作失败", it) }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun showEmergency(l: Int, t: Int, r: Int, b: Int) {
        if (emergency != null) return
        val screen = ScreenGeometry.read(this)
        val w = dp(72); val h = dp(48); val margin = dp(8)
        val candidates = listOf(margin to margin, screen.width - w - margin to margin,
            margin to screen.height - h - margin, screen.width - w - margin to screen.height - h - margin)
        val position = candidates.firstOrNull { (x, y) ->
            x >= 0 && y >= 0 && !Rect.intersects(Rect(x, y, x + w, y + h), Rect(l, t, r, b))
        } ?: error("没有可放置停止按钮的区域，请调整标记位置")
        val v = Ui.btn(this, "停止", Ui.Kind.DANGER) { Engine.stop("快捷停止") }
        val p = WindowManager.LayoutParams(w, h, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.first; y = position.second
        }
        wm.addView(v, p)
        emergency = v
    }

    /**
     * 注入期间让路。按 [InjectShield.Mode] 分两档 —— 见 [InjectShield] 的类注释。
     *
     * ## INJECT（单次点击，例如补技能）
     * 只把自有窗口切到 `FLAG_NOT_TOUCHABLE`，**不动几何位置**。窗口不跳、日志不刷。
     * 曾经这里对两档都执行 WALK 的做法，导致每补一个技能面板就被挪走再放回一次。
     *
     * ## WALK（整段走位）
     * 额外把**与注入区重叠**的窗口缩成 1×1 挪到 (0,0)，并挂一颗快捷停止按钮。
     * 为什么不只靠置灰：轮盘在左下角，而展开后的面板最高能到屏幕 88% —— 重叠是常态，
     * 这是用户实际踩到的场景（点「试走位」→ 面板吃掉注入 → 面板自己收起成球、角色不动）。
     * 置灰依赖 ROM 正确实现那个 flag；挪窗口是几何事实，不依赖任何 flag 语义。
     *
     * 挪去哪：缩成 1×1 并移到屏幕左上角。**不**用"移到屏幕外"：位置是否被 WindowManager
     * 夹回显示区取决于 ROM，1×1 在 (0,0) 则不可能压到左下角摇杆 / 右下角跳跃键。
     * 走完按原 x/y/w/h 放回去。
     */
    private fun injectGate(mode: InjectShield.Mode, on: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val walk = mode == InjectShield.Mode.WALK

        // ---- 收尾：全部恢复 ----
        if (!on) {
            val back = synchronized(vacated) {
                val copy = vacated.toList()
                vacated.clear()
                copy
            }
            onUiSync {
                injecting = false
                emergency?.let { runCatching { wm.removeView(it) } }
                emergency = null
                overlayWindows().forEach { (v, p) ->
                    p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    runCatching { wm.updateViewLayout(v, p) }
                }
                back.forEach { (v, xy) ->
                    val p = overlayWindows().firstOrNull { it.first === v }?.second ?: return@forEach
                    p.x = xy[0]; p.y = xy[1]; p.width = xy[2]; p.height = xy[3]
                    runCatching { wm.updateViewLayout(v, p) }
                }
            }
            if (back.isNotEmpty()) LogBus.emit("🛡 让路结束：${back.size} 个窗口已放回原位")
            return
        }

        // ---- 开始 ----
        val inject = Rect(l, t, r, b)
        var moved = 0
        onUiSync {
            injecting = true
            removeMarks()
            if (walk) showEmergency(l, t, r, b)
            overlayWindows().forEach { (v, p) ->
                if (walk) {
                    val location = IntArray(2)
                    v.getLocationOnScreen(location)
                    val overlaps = v.width > 0 && v.height > 0 &&
                        Rect.intersects(
                            Rect(location[0], location[1], location[0] + v.width, location[1] + v.height),
                            inject
                        )
                    if (overlaps) {
                        synchronized(vacated) {
                            vacated.add(v to intArrayOf(p.x, p.y, p.width, p.height))
                        }
                        p.x = 0; p.y = 0; p.width = 1; p.height = 1
                        moved++
                    }
                }
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
        // INJECT 是每次点击都走的路径，不写日志 —— 否则日志会被"让路"刷屏，淹掉真正的结果。
        if (walk) {
            LogBus.emit(
                if (moved > 0) "🛡 走位让路：$moved 个窗口与注入区重叠，已临时收起（走完放回）"
                else "🛡 走位让路：没有窗口与注入区重叠（仅置灰）"
            )
        }
    }

    /**
     * 自愈：万一某次恢复没跑到（超时/异常），自有窗口会一直带着 `FLAG_NOT_TOUCHABLE` ——
     * 表现为"面板看得见但点不动"。每 2 秒的轮询顺手检查一次，空闲时无条件清掉。
     */
    private fun healTouchability() {
        if (injecting || Actions.busy) return
        overlayWindows().forEach { (v, p) ->
            if (p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) {
                p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(v, p) }
                LogBus.emit("🛡 检测到窗口仍处于不可触摸状态，已自动恢复")
            }
        }
    }

    /** 版本号，写进启动日志 —— 远程排查时第一眼要看的就是"跑的是哪一版"。 */
    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun versionCode(): Long = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
    }.getOrDefault(-1L)

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
     *
     * [why] 只用来写日志：面板"自己消失"是用户实际报过的问题，日志里必须能看出
     * 每次「面板 ↔ 球」是谁触发的（用户点的 / 注入的点击打中的 / 我们主动收的）。
     */
    private fun showBall(why: String = "") {
        if (ball != null) return
        if (why.isNotBlank()) LogBus.emit("▸ 面板 → 球：$why")
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
        attachDrag(v, p, KEY_BALL_X, KEY_BALL_Y) { showPanel("点了悬浮球") }

        v.running = Engine.isRunning
        v.error = Engine.state == Engine.State.ERROR
        v.armed = foregroundPkg == targetPkg
        v.remainMs = Engine.nextBuffDueAt - android.os.SystemClock.elapsedRealtime()

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

    private fun showPanel(why: String = "") {
        if (panel != null) return
        if (why.isNotBlank()) LogBus.emit("▸ 球 → 面板：$why")
        removeBall()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 半透明黑：浮在游戏画面上，既看得清字又能透出血条/小地图
            background = Ui.shape(this@OverlayService, Ui.PANEL_BG, Ui.RADIUS_CARD, Ui.PANEL_BORDER)
            setPadding(dp(9), dp(7), dp(9), dp(9))
        }

        // ---------------- 标题栏（整条可拖动 + 点击收起）----------------
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusPill = TextView(this).apply {
            text = "…"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.PANEL_TEXT_DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 收起按钮**刻意不设 OnClickListener**：整条标题栏才是"点一下收起、按住拖动"的热区。
        // 若让这个子 View 可点，它会把 ACTION_DOWN 独吞掉，在它身上起手就拖不动了。
        val collapseBtn = TextView(this).apply {
            text = "▾"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(Ui.PANEL_TEXT_DIM)
            setPadding(dp(10), dp(3), dp(3), dp(3))
        }
        titleBar.addView(statusPill)
        titleBar.addView(collapseBtn)
        root.addView(titleBar)

        // ---------------- 内容 ----------------
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, 0)
        }

        // ① 摘要 + 启停**同一行**。
        // 启停原来是一整条 44dp 通栏按钮，加上摘要独占一行，光这两样就吃掉面板 1/4 高度。
        // 合成一行后按钮小了、也更贴近它控制的那行状态；"跑着的时候再点就是停"依然一眼可见。
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusSummary = TextView(this).apply {
            text = "…"
            setTextColor(Ui.PANEL_TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
            typeface = Typeface.MONOSPACE
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusRow.addView(statusSummary)
        mainBtn = overlayBtn(if (Engine.isRunning) "⏹ 停止" else "▶ 启动",
            fill = if (Engine.isRunning) Ui.DANGER else Ui.PANEL_CTRL_ON,
            textColor = if (Engine.isRunning) Color.WHITE else Ui.ON_PRIMARY,
            border = if (Engine.isRunning) 0 else Ui.PANEL_BORDER,
            heightDp = 34, compact = false, fixedWidthDp = 76) {
            if (Engine.isRunning) stopEngine() else startEngine()
        }
        statusRow.addView(mainBtn)
        content.addView(statusRow)
        content.addView(Ui.divider(this, 6, 4, Ui.PANEL_BORDER))

        // ② 标注区（可折叠，默认展开）—— 每颗按钮自带"已标/未标"状态
        val (annoHeader, annoBody) = collapsibleHeader("标记位置", open = !Engine.isRunning)
        annoHead = annoHeader
        annoCount = TextView(this).apply {
            text = "0/6 已标"
            setTextColor(Ui.PANEL_TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
        }
        content.addView(headerRow(annoHeader, annoCount))
        content.addView(annoBody)

        // ---- 标注按钮：一行 3 键的网格（紧凑）----
        // 原来技能 4 个挤一行 + 跳跃轮盘各占整行 = 3 行高度，且 4 个按钮每个只有 ~45dp 宽。
        // 改成一行 3 个、6 项排满 2 行：更省纵向空间，单个按钮也更宽好点。
        slotBtns.clear()
        val slotCells = Picks.ALL.map { slot ->
            overlayBtn(("○ ") + Picks.label(slot), fill = Ui.PANEL_CTRL, border = Ui.PANEL_BORDER,
                heightDp = PANEL_CELL_H, compact = true) { startSlotPick(slot) }.also { b ->
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
                slotBtns[slot] = b
            }
        }
        annoBody.addView(compactGrid(slotCells, cols = 3))

        // 「按顺序标记」主入口：面板上直接可见，不用去摸长按
        annoBody.addView(overlayBtn("按顺序标记所需位置", fill = Ui.PANEL_CTRL_ON,
            border = Ui.PANEL_BORDER, heightDp = PANEL_CELL_H, compact = false) {
            startSkillSequence(Picks.SKILL1)
        })

        cancelPickBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(overlayBtn("✕ 取消标注", fill = Ui.PANEL_CTRL, border = Ui.PANEL_BORDER,
                heightDp = PANEL_CELL_H, compact = true) { cancelSlotPick() })
        }
        annoBody.addView(cancelPickBtn)

        // ③ 工具区（可折叠，默认收起）
        val (toolHeader, toolBody) = collapsibleHeader("工具", open = false)
        content.addView(toolHeader)
        content.addView(toolBody)

        // 工具区排成一行 2 键的网格（标签比标注区更长，2 列才放得下）。
        // 4 颗整行按钮原来要占 4 行 —— 展开「工具」时面板高得离谱，也正是「点试走位、
        // 面板盖住轮盘、注入打在自己身上」那类事故的温床（见 [InjectShield]）。
        val marks = overlayBtn(marksLabel(), fill = Ui.PANEL_CTRL, border = Ui.PANEL_BORDER,
            heightDp = PANEL_CELL_H, compact = true) { toggleMarks() }
        marksBtn = marks
        toolBody.addView(compactGrid(listOf(
            marks,
            overlayBtn("试走位一次", fill = Ui.PANEL_CTRL, border = Ui.PANEL_BORDER,
                heightDp = PANEL_CELL_H, compact = true) { testStroll() },
            overlayBtn("复位窗口", fill = Ui.PANEL_CTRL, border = Ui.PANEL_BORDER,
                heightDp = PANEL_CELL_H, compact = true) { resetPositions() },
            overlayBtn("选择当前游戏", fill = Ui.PANEL_CTRL, border = Ui.PANEL_BORDER,
                heightDp = PANEL_CELL_H, compact = true) {
                Engine.stop("重新选择游戏")
                Thread {
                    val fg = runCatching { ShellCore.probe.foregroundPackage() }.getOrDefault("")
                    ui.post {
                        if (fg.isBlank() || fg == packageName) flashStatus("请先切到游戏")
                        else { setTargetPkgOf(this, fg); targetPkg = fg; flashStatus("已选择当前游戏") }
                    }
                }.apply { isDaemon = true; start() }
            }
        ), cols = 2))

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
        attachDrag(titleBar, p, KEY_PANEL_X, KEY_PANEL_Y) { showBall("点了标题条（收起）") }

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
        mainBtnRunning = null      // 按钮跟着面板一起没了，下次重建必须强制重绘一次
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
    /**
     * 高度兜底：横屏可用高度只有 720px，展开后的面板很容易顶出屏幕。
     * 把总高限制在屏幕 88% 以内，超出部分交给 ScrollView 内部滚动。
     *
     * ## 为什么要能"缩回去"
     * 曾经这里只在超高时设一个**固定**高度，内容变矮之后从不恢复 —— 于是展开过一次「工具」，
     * 之后再收起，面板会永远停在 88% 高度，下半截是空的。现在每次都按内容高度重新决定：
     * 内容装得下就回到 `WRAP_CONTENT`，装不下才钉住上限。
     */
    private fun capPanelHeight() {
        if (panel == null) return
        val scroll = panelScroll ?: return
        val exact = resources.displayMetrics.heightPixels * 0.88f
        val titleH = (statusPill?.height ?: dp(20))
        val limit = (exact.toInt() - titleH - dp(12)).coerceAtLeast(dp(60))
        // 内容实际需要多高：ScrollView 里那个子 View 的测量高度。
        val content = (scroll.getChildAt(0) as? View)?.measuredHeight ?: return
        val want = if (content > limit) limit else LinearLayout.LayoutParams.WRAP_CONTENT
        val lp = scroll.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.height == want) return
        lp.height = want
        scroll.layoutParams = lp
        scroll.requestLayout()
    }

    /** 把所有窗口位置重置回默认（右上角球 / 左上角面板）。 */
    private fun resetPositions() {
        prefs().edit()
            .remove(KEY_BALL_X).remove(KEY_BALL_Y)
            .remove(KEY_PANEL_X).remove(KEY_PANEL_Y).apply()
        val ok = ball != null || panel != null
        if (ball != null) {
            showBall("复位窗口位置")
        } else if (panel != null) {
            removePanel()
            showBall("复位窗口位置")
        }
        LogBus.emit(if (ok) "窗口位置已复位。点悬浮球重新展开。" else "窗口位置已复位。")
        flashStatus("已复位窗口位置")
    }

    // ------------------------------------------------------------ 面板控件工厂

    /**
     * 面板里的按钮。
     *
     * ## 尺寸为什么用 dp 而不是 sp
     * 覆盖层里的 sp 跟随系统字体缩放。目标机 720p 横屏下系统字号一大就撑破框。
     *
     * ## 三档高度，全在 [PANEL_CELL_H] 附近
     * 面板浮在游戏上，占的每一像素都是挡住的画面。所以网格格子和通栏按钮用同一个
     * [PANEL_CELL_H]，只有启停那颗更矮一点 —— 它跟摘要同行，需要"小一号"的视觉权重。
     * 统一高度也顺手解决了"按钮高矮不齐看着乱"。
     */
    private fun overlayBtn(
        label: String,
        fill: Int,
        border: Int = 0,
        heightDp: Int = PANEL_CELL_H,
        compact: Boolean = false,
        textColor: Int = Ui.PANEL_TEXT,
        /** 非 0 时使用固定宽度（启停那颗用，不跟同行文字抢空间）。 */
        fixedWidthDp: Int = 0,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        // 11.5dp：3 列网格里最长的标签是「○ 轮盘中心」，用 dp 而不是 sp 才不会随系统字号撑破框。
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (compact) 11.5f else 12.5f)
        setTextColor(textColor)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(2), 0, dp(2), 0)
        background = Ui.ripple(
            this@OverlayService,
            Ui.shape(this@OverlayService, fill, Ui.RADIUS_CTRL, border),
            Ui.RADIUS_CTRL
        )
        isClickable = true
        layoutParams = when {
            fixedWidthDp > 0 -> LinearLayout.LayoutParams(dp(fixedWidthDp), dp(heightDp))
            compact ->      // 网格里由父容器赋权重平分宽度
                LinearLayout.LayoutParams(0, dp(heightDp), 1f)
            else -> LinearLayout.LayoutParams(
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
     * 一行 [cols] 列的紧凑网格。
     *
     * 面板宽度只有 [PANEL_W_FULL] dp，竖着堆整行按钮的话，展开「工具」后面板会一路长到
     * 屏幕 88% 的高度（标注 + 工具共 10 颗）。网格化之后整个面板只要 4~5 行按钮。
     *
     * 列数按标签长度选：标注槽位是「○ 技能1」这种短标签 → **3 列**；
     * 工具区是「选择当前游戏」这种长标签 → **2 列**（3 列会被省略号截断）。
     *
     * 每格用 [overlayBtn] 的 `compact = true`：它已经带好 `width=0 + weight=1`，
     * 由本方法负责按行切分与间距。
     *
     * 最后一行不足 [cols] 个时补透明占位 —— 否则最后一行那颗按钮会**独占整行宽度**，
     * 和上面几行对不齐（看起来像漏了一格）。
     */
    private fun compactGrid(cells: List<View>, cols: Int): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cells.chunked(cols).forEach { rowCells ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowCells.forEach { v ->
                (v.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    marginEnd = dp(2)
                    bottomMargin = dp(2)
                }
                row.addView(v)
            }
            repeat(cols - rowCells.size) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply { marginEnd = dp(2) }
                })
            }
            box.addView(row)
        }
        return box
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
            setTextColor(Ui.PANEL_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(7), 0, dp(5))
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
        if (Engine.isRunning || Engine.isStopping || Busy.isBusy || Actions.busy || pickView != null) {
            flashStatus("请先停止任务并完成标记")
            return
        }
        Thread {
            val result = WalkFlow.strollAndJump { LogBus.emit(it) }
            LogBus.emit(result.second)
            flashStatus(result.second)
        }.apply { isDaemon = true; start() }
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
        Engine.stop("标记位置，完成后请重新启动")
        picking = true
        val generation = ++pickGeneration
        fun begin() {
            if (!running || generation != pickGeneration) return
            if (Actions.busy || Engine.isStopping) { ui.postDelayed({ begin() }, 100); return }
            openSlotPick(slot)
        }
        begin()
    }

    private fun openSlotPick(slot: String) {
        if (pickView != null) cancelSlotPick()
        val markedScreen = ScreenGeometry.read(this)
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
        v.onFinish = { ui.post { cancelSlotPick() } }
        v.onPick = { _, _, _ -> ui.post {
            if (pickView === v) {
                if (ScreenGeometry.read(this) != markedScreen) {
                    cancelSlotPick()
                    flashStatus("屏幕已变化，请重新标记")
                } else finishSlotPick()
            }
        } }

        removePanel()                       // ★ 顺序见上
        runCatching { wm.addView(v, p) }
            .onSuccess {
                pickView = v
                pickParams = p
                pickSlot = slot

                // 连续模式下把进度说清楚，用户才知道还剩几项、点完会不会自动接上下一项
                val msg = if (pickQueue.isNotEmpty()) {
                    "依次标记：" +
                        "点画面上「${Picks.label(slot)}」的位置（点完自动接下一项）"
                } else {
                    "标记「${Picks.label(slot)}」：点一下自动保存"
                }
                LogBus.emit(msg)
            }
            .onFailure {
                pickView = null
                pickSlot = null
                picking = false
                pickQueue.clear()
                LogBus.emit("标注层添加失败：${it.message}")
                ui.post { showPanel("采点层添加失败，退回面板") }
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
        Picks.required(this).filter { it !in Picks.SKILLS || Picks.SKILLS.indexOf(it) >= start }
            .forEach { pickQueue.addLast(it) }
        LogBus.emit("连续标注开始：${Picks.SKILLS.drop(start).joinToString(" → ") { Picks.label(it) }}")
        startSlotPick(pickQueue.removeFirst())
    }

    /** 用户主动取消标注（面板上的按钮）。采到一半也能退，连续模式下会中止整个队列。 */
    private fun cancelSlotPick() {
        pickGeneration++
        picking = false
        val v = pickView ?: return
        val slot = pickSlot          // 先取出来，下面会被清空
        runCatching { wm.removeView(v) }
        pickView = null
        pickParams = null
        pickSlot = null
        val wasSequence = pickQueue.isNotEmpty()
        pickQueue.clear()
        LogBus.emit(
            "已取消标注，「${slot?.let { Picks.label(it) } ?: "该项"}」未改动" +
                if (wasSequence) "；连续标注已中止" else ""
        )
        flashStatus("已取消标注")
        showPanel("取消标注")
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
        pickParams = null
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
        picking = false
        showPanel("标注完成")      // 采完回到展开面板
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
        if (injecting) { removeMarks(); return }
        val shouldShow = marksOnOf(this) && foregroundPkg == targetPkg
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

    /**
     * 回显层要画的东西：**槽位键** + 归一化坐标。
     *
     * 传槽位键而不是中文标签：标签只是显示文案，改一次文案就断一批字符串比较。
     * 颜色与文案都由 [MarksView] 从键派生。
     */
    private fun marksData(): List<Triple<String, Float, Float>> =
        Picks.ALL.mapNotNull { slot ->
            Picks.get(this, slot)?.let { Triple(slot, it.first, it.second) }
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
                    NotificationChannel(CH_ID, "悬浮控制台", NotificationManager.IMPORTANCE_LOW).apply {
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
            .addAction(android.R.drawable.ic_media_pause, "停止任务",
                android.app.PendingIntent.getService(this, 1,
                    Intent(this, OverlayService::class.java).setAction("STOP"),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
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
        color = Color.parseColor("#C70D0D0F")
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
        textPaint.color = if (running) Color.WHITE else Ui.PANEL_TEXT_DIM
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

        val screen = ScreenGeometry.read(context)
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        // ---- 轮盘中心 + 左右推杆落点 ----
        joystick?.let { (nx, ny) ->
            val cx = nx * screen.width - origin[0]
            val cy = ny * screen.height - origin[1]
            val off = pushPct / 100f * screen.width
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
        items.forEach { (slot, nx, ny) ->
            val cx = nx * screen.width - origin[0]
            val cy = ny * screen.height - origin[1]
            val label = Picks.label(slot)
            val c = colorOf(slot)
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
        val marked = items.map { it.first }.toSet()
        val missing = Picks.required(context).filter { it !in marked }
        val jumpMissing = !Picks.jumpReady(context)
        canvas.drawText(
            "${width}x${height} ${if (width > height) "横屏" else "竖屏"}  已标注 ${items.size}/${Picks.ALL.size}" +
                when {
                    missing.isNotEmpty() -> "（缺：${missing.joinToString("/") { Picks.label(it) }}）"
                    jumpMissing -> "（必需项齐了；跳跃未标，走位不跳）"
                    else -> "（齐了）"
                },
            12f, h - 14f, text
        )
    }

    /** 颜色按**槽位**而不是显示文案决定 —— 改文案不该影响配色。 */
    private fun colorOf(slot: String): Int = when (slot) {
        in Picks.SKILLS -> Color.parseColor("#FFFFC53D")
        Picks.JUMP -> Color.parseColor("#FFFF7A3D")
        else -> Color.parseColor("#FF3BC9D1")
    }
}
