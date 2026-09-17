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
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/**
 * 悬浮控制台
 * ==========
 *
 * 把测试/控制按钮**浮在游戏画面之上**，解决三个问题：
 *   1. 测试时不用再「点完立刻切到游戏」猜时间 —— 按钮就在游戏上，点一下立刻生效
 *   2. 正式挂机时，这里就是「当前场景 / 下次动作倒计时 / 总开关」的控制面板
 *   3. **可以把识别到的 ROI 直接画在游戏画面上** —— 这对后续标定与调试是质变
 *
 * ## 两个必须注意的 Window flag
 * - `FLAG_NOT_FOCUSABLE`：**绝对不能省**。悬浮窗若可获取焦点，会把游戏的输入焦点抢走，
 *   导致按键全部无效 —— 那正是我们要排查的问题，反而自己制造了一个。
 * - `FLAG_NOT_TOUCH_MODAL`：面板之外的触摸要透传给游戏，否则会挡住游戏操作。
 */
class OverlayService : Service() {

    companion object {
        private const val CH_ID = "altair_overlay"
        private const val NOTIF_ID = 1001
        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var roiView: RoiView? = null
    private var pickView: PickView? = null
    private var lastPicks: List<Pair<Float, Float>> = emptyList()
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusTv: TextView? = null
    private var logTv: TextView? = null
    private var selfBtn: Button? = null
    private val ui = Handler(Looper.getMainLooper())
    private val recent = ArrayDeque<String>()

    private val logListener: (String) -> Unit = { line ->
        ui.post {
            recent.addLast(line)
            while (recent.size > 4) recent.removeFirst()
            logTv?.text = recent.joinToString("\n")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ShellCore.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        LogBus.add(logListener)
        showPanel()
        running = true
        LogBus.emit("悬浮控制台已启动。按钮直接作用于当前前台应用（游戏）。")
        Thread {
            ShellCore.ensureRoot()
            ui.post { refreshStatus() }
        }.apply { isDaemon = true }.start()
        ui.postDelayed(statusLoop, 3000)
    }

    override fun onDestroy() {
        running = false
        ui.removeCallbacks(statusLoop)
        LogBus.remove(logListener)
        panel?.let { runCatching { wm.removeView(it) } }
        roiView?.let { runCatching { wm.removeView(it) } }
        panel = null
        roiView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 状态轮询

    private val statusLoop = object : Runnable {
        override fun run() {
            Thread {
                val s = buildStatus()
                ui.post { statusTv?.text = s }
            }.apply { isDaemon = true }.start()
            ui.postDelayed(this, 3000)
        }
    }

    private fun buildStatus(): String {
        if (!ShellCore.ensureRoot()) return "root 不可用 ⚠ 请检查红手指 root 开关"
        val focus = ShellCore.probe.focusedWindow()
        // 只显示窗口名，去掉冗长的包路径前缀
        val short = focus.replace(Regex("\\s+"), " ").take(70)
        return "焦点: $short"
    }

    private fun refreshStatus() {
        Thread {
            val s = buildStatus()
            ui.post { statusTv?.text = s }
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------ 面板

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6101317"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#3A4450"))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // ---- 标题栏（拖动把手）----
        val title = TextView(this).apply {
            text = "P0 悬浮控制台  ⠿"
            setTextColor(Color.parseColor("#7FD18B"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }
        root.addView(title)

        // ---- 状态 ----
        statusTv = TextView(this).apply {
            text = "初始化…"
            setTextColor(Color.parseColor("#8FA3B8"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(dp(2), dp(2), dp(2), dp(4))
            maxLines = 2
            // ★ 固定高度：否则文本长短变化会让整个面板忽大忽小
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30)
            )
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(statusTv)

        // ---- 按钮 ----
        root.addView(row(
            "截图" to { act("截图") { ShellCore.probe.quickCapture() } },
            "按键诊断" to { act("按键诊断") { ShellCore.probe.keyDiagnostics(3) } },
            "申请Root" to { act("申请Root") { ShellCore.probe.requestRoot() } }
        ))
        root.addView(row(
            "数字键1" to { act("数字键1") { ShellCore.probe.sendKey(8) } },
            "方向→" to { act("方向右") { ShellCore.probe.sendKey(22) } },
            "方向←" to { act("方向左") { ShellCore.probe.sendKey(21) } }
        ))
        root.addView(row(
            "★采点" to { togglePick() },
            "清点" to { clearPicks() },
            "ROI显示" to { toggleRoi() }
        ))
        root.addView(row(
            "点1" to { tapPick(0) },
            "点2" to { tapPick(1) },
            "点3" to { tapPick(2) },
            "点4" to { tapPick(3) }
        ))
        root.addView(row(
            "键扫描" to { act("键扫描") { ShellCore.probe.keyScan(3) } },
            "点1长按" to { tapPick(0, 250) },
            "复制日志" to { copyLog() }
        ))
        root.addView(row(
            "隐藏面板" to { hidePanel() }
        ))

        // ---- 最近日志 ----
        logTv = TextView(this).apply {
            text = "（日志）"
            setTextColor(Color.parseColor("#C9D4E0"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(2), dp(4), dp(2), 0)
            maxLines = 4
            // ★ 固定高度：日志行数变化不能让面板跟着变
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            )
            ellipsize = android.text.TextUtils.TruncateAt.END
            setHorizontallyScrolling(false)
        }
        root.addView(logTv)

        // ★ 固定宽度：内容长短不再影响面板尺寸，避免「随意变动大小」
        val panelW = dp(262)
        val p = WindowManager.LayoutParams(
            panelW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // ★ 必须 NOT_FOCUSABLE：否则抢走游戏输入焦点，按键全部失效
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(12)
        }

        // 拖动
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        title.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (e.rawX - downX).toInt()
                    p.y = startY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(root, p) }
                    true
                }
                else -> false
            }
        }

        panel = root
        panelParams = p
        runCatching { wm.addView(root, p) }
            .onFailure { LogBus.emit("悬浮窗添加失败: ${it.message}（多半是没有悬浮窗权限）") }
    }

    private fun row(vararg btns: Pair<String, () -> Unit>): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.forEach { (label, fn) ->
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(4), 0, dp(4), 0)
                minWidth = 0
                minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
                    .apply { marginEnd = dp(3) }
                setOnClickListener { fn() }
            }
            r.addView(b)
        }
        return r
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        LogBus.emit("面板已隐藏。回到 App 点「启动悬浮窗」可再次显示。")
        // 仍然保留服务与 ROI 层；真正的退出由 App 里的「停止悬浮窗」负责
    }

    // ------------------------------------------------------------ 动作

    private fun act(label: String, block: () -> String) {
        LogBus.emit("▸ $label")
        Thread {
            val r = runCatching { block() }.getOrElse { "出错: ${it.javaClass.simpleName}: ${it.message}" }
            LogBus.emit(r.trimEnd())
        }.apply { isDaemon = true }.start()
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("probe", LogBus.dump()))
        LogBus.emit("已复制日志到剪贴板（${LogBus.dump().length} 字）")
    }

    // ------------------------------------------------------------ 坐标采集

    /**
     * 切换坐标采集模式。
     *
     * 打开后铺一层**全屏透明可触摸**的覆盖层：你直接在游戏画面上点技能键的位置，
     * 每次点击都会被记录并画上编号。采点期间游戏收不到点击（正好，不该有副作用）。
     * 采完点切换回来，就能用「点1..点4」按采集到的坐标做触摸测试。
     */
    private fun togglePick() {
        if (pickView != null) { stopPick(); return }
        val v = PickView(this)
        v.onPick = { idx, nx, ny ->
            LogBus.emit("采点 $idx: [%.4f, %.4f]".format(nx, ny))
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // 可触摸（要接收点击）但不吃焦点（否则会抢游戏输入焦点）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        v.onFinish = { ui.post { stopPick() } }

        // ★ 顺序很重要：采集层是全屏可触摸的，若它加在面板之后就会盖住面板，
        //   用户便无法回去点「★采点」来关闭 —— 会直接被困住。
        //   所以先摘下面板 → 加采集层 → 再把面板加回去，保证面板在最上层可点。
        panel?.let { runCatching { wm.removeView(it) } }
        runCatching { wm.addView(v, p) }
            .onSuccess {
                pickView = v
                panel?.let { pnl -> panelParams?.let { pp -> runCatching { wm.addView(pnl, pp) } } }
                LogBus.emit("采点模式：已开启 —— 在游戏画面上依次点技能键位置；点完点画面下方的「完成采点」")
            }
            .onFailure { LogBus.emit("采点层添加失败: ${it.message}") }
    }

    private fun stopPick() {
        val v = pickView ?: return
        runCatching { wm.removeView(v) }
        pickView = null
        lastPicks = v.points.toList()      // 关掉后仍能用来「点N」
        LogBus.emit("采点模式：已关闭，共 ${lastPicks.size} 个点")
        LogBus.emit(v.export())
        // 面板可能因为日志变长而需要重排，刷新一下状态
        ui.post { refreshStatus() }
    }

    private fun clearPicks() {
        pickView?.clearAll()
        LogBus.emit("已清空采集点")
    }

    /** 点击第 idx 个采集点（从 0 开始）。 */
    private fun tapPick(idx: Int, pressMs: Int = 90) {
        val v = pickView
        // 采点层开着时也能点：先从它拿；关掉时从最后一次的副本拿
        val pts = v?.points ?: lastPicks
        if (idx >= pts.size) {
            LogBus.emit("点${idx + 1}：还没采集到该位置（当前共 ${pts.size} 个）")
            return
        }
        val (nx, ny) = pts[idx]
        act("点${idx + 1}") {
            ShellCore.probe.tapNorm(nx.toDouble(), ny.toDouble(), "点${idx + 1}", pressMs)
        }
    }

    // ------------------------------------------------------------ ROI 覆盖层    // ------------------------------------------------------------ ROI 覆盖层

    private fun toggleRoi() {
        if (roiView != null) {
            roiView?.let { runCatching { wm.removeView(it) } }
            roiView = null
            LogBus.emit("ROI 覆盖层：已关闭")
            return
        }
        val v = RoiView(this)
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
            .onSuccess {
                roiView = v
                LogBus.emit("ROI 覆盖层：已开启（绿=小地图 红=血条 黄=技能键带 青=中心）")
            }
            .onFailure { LogBus.emit("ROI 覆盖层添加失败: ${it.message}") }
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
        return b.setContentTitle("P0 探测 · 悬浮控制台运行中")
            .setContentText("按钮悬浮在游戏之上，可直接测试")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }
}

/**
 * ROI 调试覆盖层。
 *
 * 把配置里的归一化 ROI 直接画在游戏画面上 —— 框对不对，一眼就能看出来。
 * 这比反复截图、分析、打印坐标高效得多。
 */
class RoiView(ctx: Context) : View(ctx) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        typeface = Typeface.MONOSPACE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // 来自 config/rules.example.json 的已验证数值
    private val rois = listOf(
        Triple("小地图", floatArrayOf(0.0023f, 0.1097f, 0.1477f, 0.2958f), Color.parseColor("#FF3BD16F")),
        Triple("HUD血条", floatArrayOf(0.4289f, 0.8958f, 0.5711f, 0.9125f), Color.parseColor("#FFFF4444")),
        // 技能键带：边界由 v0.13 实机采点结果反推（不再是最初的推算值）
        Triple("技能键带", floatArrayOf(0.7150f, 0.5350f, 0.9500f, 0.6150f), Color.parseColor("#FFFFC53D"))
    )

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 技能键 4 个位置 —— **实机采点实测值**（此前推算的偏左了约 0.06）
        val skillPts = arrayOf(
            0.7416f to 0.5673f,
            0.8041f to 0.5756f,
            0.8649f to 0.5673f,
            0.9180f to 0.5728f
        )

        // 归一化 1/10 网格（细线，帮助读数）
        stroke.strokeWidth = 1f
        stroke.color = Color.parseColor("#33FFFFFF")
        for (i in 1..9) {
            canvas.drawLine(w * i / 10f, 0f, w * i / 10f, h, stroke)
            canvas.drawLine(0f, h * i / 10f, w, h * i / 10f, stroke)
        }

        // 各 ROI
        stroke.strokeWidth = 4f
        for ((name, r, c) in rois) {
            stroke.color = c
            val rect = RectF(r[0] * w, r[1] * h, r[2] * w, r[3] * h)
            canvas.drawRect(rect, stroke)
            fill.color = c
            canvas.drawRect(rect.left, max(0f, rect.top - 30f), rect.left + text.measureText(name) + 10f, rect.top, fill)
            text.color = Color.BLACK
            canvas.drawText(name, rect.left + 5f, max(24f, rect.top - 6f), text)
        }

        // 技能键位置点
        stroke.color = Color.parseColor("#FFFFC53D")
        stroke.strokeWidth = 3f
        for ((x, y) in skillPts) {
            val cx = x * w; val cy = y * h
            canvas.drawCircle(cx, cy, 16f, stroke)
            canvas.drawLine(cx - 26f, cy, cx + 26f, cy, stroke)
            canvas.drawLine(cx, cy - 26f, cx, cy + 26f, stroke)
        }

        // 屏幕中心
        stroke.color = Color.parseColor("#FF3BC9D1")
        stroke.strokeWidth = 3f
        canvas.drawCircle(w / 2f, h / 2f, 22f, stroke)
        canvas.drawLine(w / 2f - 40f, h / 2f, w / 2f + 40f, h / 2f, stroke)
        canvas.drawLine(w / 2f, h / 2f - 40f, w / 2f, h / 2f + 40f, stroke)

        // 尺寸标注
        text.color = Color.parseColor("#FF3BC9D1")
        canvas.drawText("${width}x${height}  ${if (width > height) "横屏" else "竖屏"}", 12f, h - 14f, text)
    }
}
