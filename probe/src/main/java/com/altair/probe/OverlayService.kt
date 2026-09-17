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
import org.json.JSONArray
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
        /** 面板宽度（dp）：展开 / 精简。固定宽度避免内容长短导致面板忽大忽小。 */
        private const val PANEL_W_FULL = 208
        private const val PANEL_W_COMPACT = 104
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

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("overlay", Context.MODE_PRIVATE)

        // ---- 供集控（Management）读写的静态入口 ----
        // 集控下发配置时需要改这些值，但它是另一个类，拿不到 Service 实例，
        // 所以走 SharedPreferences 这个共享存储，下次服务启动/刷新时生效。

        /** 读取门禁目标包名。 */
        fun targetPkgOf(ctx: Context): String =
            prefs(ctx).getString("targetPkg", "com.nexon.mod") ?: "com.nexon.mod"

        /** 写入门禁目标包名。 */
        fun setTargetPkgOf(ctx: Context, pkg: String) {
            prefs(ctx).edit().putString("targetPkg", pkg).apply()
        }

        /** 保存采集点（集控下发后，重启悬浮窗即可用新坐标）。 */
        fun savePickedPointsOf(ctx: Context, pts: List<Pair<Float, Float>>) {
            val arr = JSONArray()
            pts.forEach { (x, y) ->
                arr.put(JSONArray().put(x.toDouble()).put(y.toDouble()))
            }
            prefs(ctx).edit().putString("pickedPoints", arr.toString()).apply()
        }

        /** 读取采集点。 */
        fun pickedPointsOf(ctx: Context): List<Pair<Float, Float>> {
            val raw = prefs(ctx).getString("pickedPoints", "") ?: ""
            if (raw.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val p = arr.optJSONArray(i) ?: return@mapNotNull null
                    if (p.length() < 2) null
                    else p.optDouble(0).toFloat() to p.optDouble(1).toFloat()
                }
            }.getOrDefault(emptyList())
        }
    }

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var roiView: RoiView? = null
    private var pickView: PickView? = null
    private var lastPicks: List<Pair<Float, Float>> = emptyList()
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusTvRef: TextView? = null
    private var statusPill: TextView? = null
    private var collapseBtn: TextView? = null
    private var contentBox: LinearLayout? = null
    private var contentScroll: android.widget.ScrollView? = null
    /** 精简模式：只显示一行运行状态，按钮全部收起。 */
    private var compact = true
    /**
     * 按压方式循环。同一位置用不同按法试，是定位「游戏接受哪种触摸」的最快办法，
     * 但为它单开三个按钮太占地方 —— 合并成一个循环按钮。
     */
    private val pressModes = listOf(
        Triple("短50", 50, "swipe"),
        Triple("中90", 90, "swipe"),
        Triple("长250", 250, "swipe"),
        Triple("自绘150", 150, "motionevent")
    )
    private var pressIdx = 1
    private var pressBtn: Button? = null
    /** 门禁：目标游戏包名。只有它在前台时，输入类动作才允许执行。 */
    private var targetPkg: String = "com.nexon.mod"
    /** ROI 的用户意图（≠ 实际是否显示：实际显示还要满足「游戏在前台」）。 */
    private var roiEnabled = false
    /** 最近一次检测到的前台包名。 */
    private var lastFg: String = "?"
    private var statusTv: TextView? = null
    private var logTv: TextView? = null
    private var selfBtn: Button? = null
    private val ui = Handler(Looper.getMainLooper())
    private val recent = ArrayDeque<String>()

    /** 悬浮窗不再显示日志（日志看主界面的日志页），这里只做启动提示，避免无谓刷新。 */
    private val logListener: (String) -> Unit = { /* no-op */ }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prefs() = getSharedPreferences("overlay", MODE_PRIVATE)

    override fun onCreate() {
        super.onCreate()
        ShellCore.init(this)
        targetPkg = targetPkgOf(this)
        lastPicks = pickedPointsOf(this)
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
        val fg = ShellCore.probe.foregroundPackage()
        lastFg = fg.ifBlank { "?" }
        syncRoiWithForeground(fg)
        val armed = fg == targetPkg
        ui.post {
            statusPill?.text = if (armed) "🟢 游戏中" else "🔴 非游戏"
            statusPill?.setTextColor(
                Color.parseColor(if (armed) "#7FD18B" else "#FFB454")
            )
        }
        return buildString {
            append(if (armed) "🟢 游戏中 · 动作已启用" else "🔴 非游戏 · 动作已禁用")
            append('\n')
            append("前台: ").append(if (fg.isBlank()) "未知" else fg)
            append('\n').append("目标: ").append(targetPkg)
        }
    }

    /**
     * ROI 只在「用户开启了 ROI」且「目标游戏处于前台」时显示。
     * 游戏不在前台就自动隐藏 —— 免得把识别框画在别的界面上，既没意义又容易误解。
     */
    private fun syncRoiWithForeground(fg: String) {
        val shouldShow = roiEnabled && fg == targetPkg
        val showing = roiView != null
        if (shouldShow && !showing) addRoi()
        else if (!shouldShow && showing) removeRoi()
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
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#3A4450"))
            }
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }

        // ---------------- 标题栏：状态胶囊 + 折叠按钮（整条可拖动） ----------------
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusPill = TextView(this).apply {
            text = "⚪ 初始化"
            setTextColor(Color.parseColor("#E6EDF5"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(3), dp(1), dp(2), dp(1))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, dp(20), 1f)
        }
        collapseBtn = TextView(this).apply {
            text = "▸"          // 默认精简
            setTextColor(Color.parseColor("#7FD18B"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(1), dp(4), dp(1))
            setOnClickListener { toggleCompact() }
        }
        titleBar.addView(statusPill)
        titleBar.addView(collapseBtn)
        root.addView(titleBar)

        // ---------------- 可折叠内容 ----------------
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, 0)
        }

        statusTv = TextView(this).apply {
            text = "…"
            setTextColor(Color.parseColor("#8FA3B8"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8f)
            setPadding(dp(2), dp(1), dp(2), dp(3))
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)
            )
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        content.addView(statusTv)

        // 面板只留「调试最常用」的两行：
        //   ROI 显示 / 采点 / 清点 / 标定游戏
        //   技能键 1-4 的模拟点击
        // 其余（截图、诊断、键扫描、复制日志、申请Root）都移到主界面的日志页，
        // 因为那些是排查时用的，不是挂在游戏上天天点的。
        content.addView(row(
            "ROI显示" to { toggleRoi() },
            "★采点" to { togglePick() },
            "清点" to { clearPicks() },
            "标定" to { calibrateTarget() }
        ))
        content.addView(row(
            "点1" to { tapPick(0) },
            "点2" to { tapPick(1) },
            "点3" to { tapPick(2) },
            "点4" to { tapPick(3) }
        ))
        content.addView(row(
            "菜单" to { tapPick(4) },
            "自由市场" to { tapPick(5) },
            "按法" to { cyclePressMode() }
        ))

        contentBox = content

        // 内容放进 ScrollView —— 万一内容还是超高，可以滚动而不是顶出屏幕
        val scroll = android.widget.ScrollView(this).apply {
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = true
        }
        contentScroll = scroll
        root.addView(scroll)

        // ---------------- 窗口参数 ----------------
        val p = WindowManager.LayoutParams(
            dp(PANEL_W_FULL),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // ★ 必须 NOT_FOCUSABLE：否则抢走游戏输入焦点，按键全部失效
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(10)
        }

        // 拖动（标题栏整条都能拖）
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        titleBar.setOnTouchListener { _, e ->
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
            .onSuccess {
                // 默认进精简模式（只显示运行状态），点 ▸ 展开
                applyCompact()
                // ★ 高度兜底：横屏只有 720px 高，展开后很容易顶出屏幕。
                //   量一次实际高度，超了就限制 ScrollView 的高度让它内部滚动。
                root.post { capPanelHeight() }
            }
            .onFailure { LogBus.emit("悬浮窗添加失败: ${it.message}（多半是没有悬浮窗权限）") }
    }

    /**
     * 高度兜底。
     * 横屏 1280x720 时可用高度只有 720px，而展开后的面板内容很容易超过它。
     * 这里把面板总高限制在屏幕的 88% 以内，超出部分交给 ScrollView 内部滚动。
     */
    private fun capPanelHeight() {
        val root = panel ?: return
        val scroll = contentScroll ?: return
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

    /** 切换精简 / 展开。 */
    private fun toggleCompact() {
        compact = !compact
        applyCompact()
    }

    private fun applyCompact() {
        contentScroll?.visibility = if (compact) View.GONE else View.VISIBLE
        collapseBtn?.text = if (compact) "▸" else "▾"
        panelParams?.let { p ->
            p.width = dp(if (compact) PANEL_W_COMPACT else PANEL_W_FULL)
            panel?.let { v -> runCatching { wm.updateViewLayout(v, p) } }
            if (!compact) panel?.post { capPanelHeight() }
        }
        LogBus.emit(if (compact) "面板：精简模式（只显示运行状态）" else "面板：展开")
    }

    /** 循环切换按压方式，按钮文字同步显示当前档位。 */
    private fun cyclePressMode() {
        pressIdx = (pressIdx + 1) % pressModes.size
        val m = pressModes[pressIdx]
        LogBus.emit("按压方式 -> ${m.first}（${m.second}ms / ${m.third}）")
        updatePressBtn()
    }

    private fun updatePressBtn() {
        pressBtn?.text = "按法:${pressModes[pressIdx].first}"
    }

    private fun row(vararg btns: Pair<String, () -> Unit>): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.forEach { (label, fn) ->
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
                setPadding(dp(2), 0, dp(2), 0)
                minWidth = 0
                minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f)
                    .apply { marginEnd = dp(2) }
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

    /**
     * 执行一个动作。
     *
     * [guard] 为 true 时先做**前台门禁**：只有目标游戏在前台才执行。
     * 这是防止「在错误界面上乱点」的关键 —— 焦点一旦跑到桌面、系统弹窗或本应用上，
     * 注入的点击/按键就会打到错误的地方。
     */
    private fun act(label: String, guard: Boolean = true, block: () -> String) {
        LogBus.emit("▸ $label")
        Thread {
            if (guard) {
                val fg = ShellCore.probe.foregroundPackage()
                if (fg != targetPkg) {
                    LogBus.emit(
                        "   ⛔ 已跳过：当前前台是「${fg.ifBlank { "未知" }}」，" +
                            "不是目标游戏「$targetPkg」"
                    )
                    return@Thread
                }
            }
            val r = runCatching { block() }.getOrElse { "出错: ${it.javaClass.simpleName}: ${it.message}" }
            LogBus.emit(r.trimEnd())
        }.apply { isDaemon = true }.start()
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("probe", LogBus.dump()))
        LogBus.emit("已复制日志到剪贴板（${LogBus.dump().length} 字）")
    }

    /**
     * 把「当前前台应用」记为门禁目标。
     * 用法：切到游戏，点一下本按钮即可 —— 不用手打包名。
     */
    private fun calibrateTarget() {
        Thread {
            val fg = ShellCore.probe.foregroundPackage()
            if (fg.isBlank()) {
                LogBus.emit("标定游戏：取不到当前前台包名（root 不可用？）")
                return@Thread
            }
            if (fg == packageName) {
                LogBus.emit("标定游戏：当前前台是本应用自己，请先切到游戏再点。")
                return@Thread
            }
            targetPkg = fg
            prefs().edit().putString("targetPkg", fg).apply()
            LogBus.emit("标定游戏：目标已设为「$fg」。此后只有它在前台时才执行动作。")
        }.apply { isDaemon = true }.start()
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
        runCatching { savePickedPointsOf(this, lastPicks) }   // 持久化，重启后仍在
        if (roiEnabled) { removeRoi(); syncRoiWithForeground(lastFg) }   // ROI 立刻反映新点
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
    private fun tapPick(idx: Int, pressMs: Int = -1, method: String = "") {
        // 未显式指定时，用「按法」按钮当前选中的档位
        val cur = pressModes[pressIdx]
        val useMs = if (pressMs < 0) cur.second else pressMs
        val useMethod = method.ifBlank { cur.third }
        val v = pickView
        // 采点层开着时也能点：先从它拿；关掉时从最后一次的副本拿
        val pts = v?.points ?: lastPicks
        if (idx >= pts.size) {
            LogBus.emit("点${idx + 1}：还没采集到该位置（当前共 ${pts.size} 个）")
            return
        }
        val (nx, ny) = pts[idx]
        act("点${idx + 1}") {
            ShellCore.probe.tapNorm(nx.toDouble(), ny.toDouble(), "点${idx + 1}", useMs, useMethod)
        }
    }

    // ------------------------------------------------------------ ROI 覆盖层    // ------------------------------------------------------------ ROI 覆盖层

    private fun toggleRoi() {
        roiEnabled = !roiEnabled
        if (!roiEnabled) {
            removeRoi()
            LogBus.emit("ROI 覆盖层：已关闭")
            return
        }
        LogBus.emit("ROI 覆盖层：已开启（仅在游戏处于前台时显示）")
        syncRoiWithForeground(ShellCore.probe.foregroundPackage())
    }

    private fun removeRoi() {
        roiView?.let { runCatching { wm.removeView(it) } }
        roiView = null
    }

    private fun addRoi() {
        if (roiView != null) return
        // 把采集点传进去，ROI 上就会按语义标注「技能1..4 / 菜单 / 自由市场」
        val v = RoiView(this, lastPicks)
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
            .onSuccess { roiView = v }
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
 * ROI 调试覆盖层
 * ==============
 *
 * 把配置里的归一化 ROI 和采集到的按键位置**直接画在游戏画面上**，框对不对一眼可见。
 *
 * 所有数值都来自实机实测，不是推算：
 *   · 小地图 / HUD 三柱：tools/analyze 从实机截图量出来的精确像素框
 *   · 技能键 / 菜单 / 自由市场：用户用「采点」亲自点的
 */
class RoiView(
    ctx: Context,
    /** 采集到的点：0-3=技能键 4=菜单 5=自由市场（按采点顺序） */
    private val picked: List<Pair<Float, Float>> = emptyList()
) : View(ctx) {

    /** 采集点的语义标签（按采点顺序）。 */
    private val pickLabels = arrayOf("技能1", "技能2", "技能3", "技能4", "菜单", "自由市场", "传送点", "备用")

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = Typeface.MONOSPACE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * 静态 ROI —— 全部为实机实测值。
     * HUD 三柱结构完全对称（每 22 行一组：2px 边框 + 12px 填充），实测于 1280x720。
     */
    private val rois = listOf(
        Triple("小地图", floatArrayOf(0.0023f, 0.1097f, 0.1477f, 0.2958f), Color.parseColor("#FF3BD16F")),
        Triple("血", floatArrayOf(0.4289f, 0.8958f, 0.5711f, 0.9125f), Color.parseColor("#FFFF4444")),
        Triple("蓝", floatArrayOf(0.4289f, 0.9264f, 0.5711f, 0.9431f), Color.parseColor("#FF4A9BFF")),
        Triple("经验", floatArrayOf(0.4289f, 0.9569f, 0.5711f, 0.9736f), Color.parseColor("#FFFFC53D")),
        // 菜单面板：右侧整条竖排（对上两张截图做亮度补偿后相减得到，仅此一个连通域）
        Triple("菜单面板", floatArrayOf(0.8008f, 0.0f, 1.0f, 1.0f), Color.parseColor("#FFB07CFF")),
        // 技能键带：由采点结果反推的范围
        Triple("技能带", floatArrayOf(0.7150f, 0.5350f, 0.9500f, 0.6150f), Color.parseColor("#FFFFC53D"))
    )

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1/10 归一化网格，方便肉眼读坐标
        stroke.strokeWidth = 1f
        stroke.color = Color.parseColor("#33FFFFFF")
        for (i in 1..9) {
            canvas.drawLine(w * i / 10f, 0f, w * i / 10f, h, stroke)
            canvas.drawLine(0f, h * i / 10f, w, h * i / 10f, stroke)
        }

        // ---- 静态 ROI 框 ----
        stroke.strokeWidth = 3f
        for ((name, r, c) in rois) {
            stroke.color = c
            val rect = RectF(r[0] * w, r[1] * h, r[2] * w, r[3] * h)
            canvas.drawRect(rect, stroke)
            // 标签贴在框的左上角（超出屏幕就贴框内）
            fill.color = c
            val tw = text.measureText(name) + 10f
            val ty = if (rect.top > 26f) rect.top else rect.top + 26f
            canvas.drawRect(rect.left, ty - 24f, rect.left + tw, ty, fill)
            text.color = Color.BLACK
            canvas.drawText(name, rect.left + 5f, ty - 6f, text)
        }

        // ---- 采集点（技能键 / 菜单 / 自由市场 …）----
        picked.forEachIndexed { i, (nx, ny) ->
            val cx = nx * w
            val cy = ny * h
            val c = when (i) {
                0, 1, 2, 3 -> Color.parseColor("#FFFFC53D")   // 技能键 = 黄
                4 -> Color.parseColor("#FFB07CFF")            // 菜单 = 紫
                5 -> Color.parseColor("#FF3BD16F")            // 自由市场 = 绿
                else -> Color.parseColor("#FF3BC9D1")
            }
            stroke.color = c
            stroke.strokeWidth = 4f
            canvas.drawCircle(cx, cy, 26f, stroke)
            canvas.drawLine(cx - 38f, cy, cx + 38f, cy, stroke)
            canvas.drawLine(cx, cy - 38f, cx, cy + 38f, stroke)
            val label = pickLabels.getOrElse(i) { "点${i + 1}" }
            fill.color = c
            val tw = text.measureText(label) + 10f
            canvas.drawRect(cx + 30f, cy - 46f, cx + 30f + tw, cy - 20f, fill)
            text.color = Color.BLACK
            canvas.drawText(label, cx + 35f, cy - 28f, text)
        }

        // ---- 屏幕中心 ----
        stroke.color = Color.parseColor("#FF3BC9D1")
        stroke.strokeWidth = 2f
        canvas.drawCircle(w / 2f, h / 2f, 18f, stroke)

        // ---- 尺寸标注 ----
        text.color = Color.parseColor("#FF3BC9D1")
        canvas.drawText("${width}x${height} ${if (width > height) "横屏" else "竖屏"}  采集点 ${picked.size}",
            12f, h - 14f, text)
    }
}
