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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * 悬浮控制台
 * ==========
 *
 * 挂在游戏画面上的小面板，只有四件事：
 * ```
 *   ① 启动 / 停止挂机任务
 *   ② 标注坐标：标技能1..4 / 标跳跃 / 标轮盘（点一下按钮，再点画面上那个位置）
 *   ③ 标点回显：把标好的点画在画面上，确认位置对不对
 *   ④ 试走位一次：手动跑一遍「左 D → 右 2D → 左 D → 跳」
 * ```
 *
 * ## 为什么标注必须由用户点一下
 * 见 [Picks] 的类注释：自动识别一旦偏了，代价是点到游戏里别的地方，且从日志看不出来。
 * 手动标注一次几秒，永远不会偏。
 *
 * ## 标注与"改一个点"
 * 每个槽位**独立标注、独立保存**（改技能2 不会碰到技能1）—— 这是从"整批重采"换过来的：
 * 整批重采时只想改一个菜单坐标也得把所有点重采一遍，采一半退出还会把已标好的全冲掉。
 *
 * ## 前台门禁
 * 所有输入类动作默认先检查"目标游戏是否在前台"（[act] 的 guard 参数）。
 * 焦点一旦跑到桌面、系统弹窗或本应用上，注入的点击就会打到错误的地方。
 */
class OverlayService : Service() {

    companion object {
        private const val CH_ID = "altair_overlay"
        private const val NOTIF_ID = 1001

        /** 展开态面板宽度（dp）。四列标注按钮要放得下。 */
        private const val PANEL_W_FULL = 214

        /** 精简态只留标题条。 */
        private const val PANEL_W_COMPACT = 104

        /** 标点回显开关的持久化键（放 overlay prefs，和门禁目标同一份）。 */
        private const val KEY_MARKS = "marksOn"

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
    private var marksView: MarksView? = null
    private var pickView: PickView? = null

    /** 正在标注的槽位（null = 没在标注）。 */
    private var pickSlot: String? = null

    private var panelParams: WindowManager.LayoutParams? = null
    private var statusPill: TextView? = null
    private var statusTv: TextView? = null
    private var collapseBtn: TextView? = null
    private var contentBox: LinearLayout? = null
    private var contentScroll: ScrollView? = null
    private var marksBtn: Button? = null

    private var compact = true

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
        showPanel()
        running = true
        LogBus.emit("悬浮控制台已启动。标注坐标 → 启动任务，全部动作只作用于目标游戏。")
        Thread {
            ShellCore.ensureRoot()
            ui.post { refreshStatus() }
        }.apply { isDaemon = true }.start()
        ui.postDelayed(statusLoop, 1500)
    }

    override fun onDestroy() {
        running = false
        ui.removeCallbacks(statusLoop)
        LogBus.remove(logListener)
        panel?.let { runCatching { wm.removeView(it) } }
        marksView?.let { runCatching { wm.removeView(it) } }
        pickView?.let { runCatching { wm.removeView(it) } }
        panel = null
        marksView = null
        pickView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 状态轮询

    private val statusLoop = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { refreshStatus() }
            runCatching { syncMarks() }
            ui.postDelayed(this, 2000)
        }
    }

    private fun refreshStatus() {
        val s = buildStatus()
        statusTv?.text = s
        statusPill?.text = shortStatus()
        statusPill?.setTextColor(Color.parseColor(pillColor()))
    }

    private fun buildStatus(): String {
        val fg = runCatching { ShellCore.probe.foregroundPackage() }.getOrDefault("")
        lastFg = fg
        val armed = fg == targetPkg
        val sb = StringBuilder()
        sb.append(if (armed) "🟢 目标游戏在前台" else "🔴 非目标游戏（动作已禁用）")
        sb.append('\n')
        if (Engine.isRunning) {
            sb.append("补BUFF ").append(Engine.countdown(Engine.nextBuffDueAt))
            sb.append(" · 走位 ").append(Engine.countdown(Engine.nextWalkDueAt))
            sb.append('\n')
            sb.append("累计：补 ").append(Engine.buffCastCount).append(" 次 / 走 ")
                .append(Engine.walkCount).append(" 次")
            if (Engine.failStreak > 0) sb.append(" / 连败 ").append(Engine.failStreak)
        } else {
            sb.append("引擎未启动")
            sb.append('\n')
            sb.append("标注：").append(annotationSummary())
        }
        return sb.toString()
    }

    private fun annotationSummary(): String {
        val skills = if (Picks.skillsReady(this)) "技能1-4✅" else
            "技能缺" + Picks.missingSkills(this).joinToString("/")
        val jump = if (Picks.get(this, Picks.JUMP) != null) "跳跃✅" else "跳跃❌"
        val joy = if (Picks.joystickAnnotated(this)) "轮盘✅" else "轮盘用默认"
        return "$skills $jump $joy"
    }

    private fun shortStatus(): String = when {
        !Engine.isRunning -> "已停止"
        Engine.state == Engine.State.ERROR -> "⛔ 熔断"
        Engine.state == Engine.State.CASTING -> "▶ 执行中"
        lastFg == targetPkg -> "▶ 运行中"
        else -> "⏸ 游戏不在前台"
    }

    private fun pillColor(): String = when {
        !Engine.isRunning -> "#8FA3B8"
        Engine.state == Engine.State.ERROR -> "#FF6B6B"
        lastFg == targetPkg -> "#7FD18B"
        else -> "#FFB454"
    }

    /** 短暂提示：把状态条临时替换成一条消息，2.5 秒后回到正常状态。 */
    private fun flashStatus(msg: String) {
        statusPill?.text = msg.take(40)
        ui.postDelayed({ if (running) refreshStatus() }, 2500)
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
            setBackgroundColor(Color.parseColor("#E614191F"))
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }

        // ---------------- 标题栏（整条可拖动）----------------
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusPill = TextView(this).apply {
            text = "…"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8FA3B8"))
            setPadding(dp(4), dp(2), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        collapseBtn = TextView(this).apply {
            text = "▸"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#C9D4E0"))
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener { toggleCompact() }
        }
        titleBar.addView(statusPill)
        titleBar.addView(collapseBtn)
        root.addView(titleBar)

        // ---------------- 内容 ----------------
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        statusTv = TextView(this).apply {
            text = "…"
            setTextColor(Color.parseColor("#8FA3B8"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8.5f)
            setPadding(dp(2), dp(1), dp(2), dp(4))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        content.addView(statusTv)

        content.addView(rowBig(
            "▶ 启动" to { startEngine() },
            "⏹ 停止" to { stopEngine() }
        ))
        content.addView(divider())

        // 标注按钮：每一项独立标注、独立保存（改一个点不动其它点）
        content.addView(row(
            "标技能1" to { startSlotPick(Picks.SKILL1) },
            "标技能2" to { startSlotPick(Picks.SKILL2) },
            "标技能3" to { startSlotPick(Picks.SKILL3) },
            "标技能4" to { startSlotPick(Picks.SKILL4) }
        ))
        marksBtn = null
        val marksRow = row(
            "标跳跃" to { startSlotPick(Picks.JUMP) },
            "标轮盘" to { startSlotPick(Picks.JOYSTICK) }
        )
        val mb = Button(this).apply {
            text = marksLabel()
            isAllCaps = false
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(dp(2), 0, dp(2), 0)
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f).apply { marginEnd = dp(2) }
            setOnClickListener { toggleMarks() }
        }
        marksBtn = mb
        marksRow.addView(mb)
        content.addView(marksRow)

        content.addView(row(
            "试走位一次" to { testStroll() },
            "清空标注" to { clearAllPicks() }
        ))

        contentBox = content

        val scroll = ScrollView(this).apply {
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
            // ★ 必须 NOT_FOCUSABLE：否则抢走游戏输入焦点，按键/触摸全部失效
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(10)
        }

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
                applyCompact()
                root.post { capPanelHeight() }
                refreshStatus()
            }
            .onFailure { LogBus.emit("悬浮窗添加失败: ${it.message}（多半是没有悬浮窗权限）") }
    }

    private fun marksLabel() = if (marksOnOf(this)) "回显:开" else "回显:关"

    /**
     * 高度兜底：横屏可用高度只有 720px，展开后的面板很容易顶出屏幕。
     * 把总高限制在屏幕 88% 以内，超出部分交给 ScrollView 内部滚动。
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
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(5); bottomMargin = dp(5) }
        setBackgroundColor(Color.parseColor("#313A45"))
    }

    private fun row(vararg btns: Pair<String, () -> Unit>): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.forEach { (label, fn) ->
            r.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
                setPadding(dp(2), 0, dp(2), 0)
                minWidth = 0
                minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f)
                    .apply { marginEnd = dp(2) }
                setOnClickListener { fn() }
            })
        }
        return r
    }

    private fun rowBig(vararg btns: Pair<String, () -> Unit>): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.forEach { (label, fn) ->
            r.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(4), 0, dp(4), 0)
                minWidth = 0
                minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
                    .apply { marginEnd = dp(3) }
                setOnClickListener { fn() }
            })
        }
        return r
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
            ui.post { refreshStatus() }
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
        ui.post { refreshStatus() }
    }

    private fun stopEngine() {
        Engine.stop("悬浮窗停止")
        val msg = "⏹ 任务已停止"
        LogBus.emit(msg)
        flashStatus(msg)
        ui.post { refreshStatus() }
    }

    /** 手动试一次走位（不补 BUFF，纯验证走位闭环）。 */
    private fun testStroll() {
        Thread {
            LogBus.emit("▸ 试走位一次")
            LogBus.emit("   " + WalkFlow.describe(this).replace("\n", "；"))
            val (ok, msg) = WalkFlow.strollAndJump { LogBus.emit(it) }
            LogBus.emit(if (ok) "   ✅ $msg" else "   ⚠ $msg")
            flashStatus(if (ok) "✅ 走位完成" else "⚠ 走位有问题")
            ui.post { refreshStatus() }
        }.apply { isDaemon = true }.start()
    }

    private fun clearAllPicks() {
        Picks.clearAll(this)
        LogBus.emit("已清空全部标注（技能1-4 / 跳跃 / 轮盘）。")
        flashStatus("已清空标注")
        marksView?.let { runCatching { wm.removeView(it) } }
        marksView = null
        ui.post { refreshStatus() }
    }

    // ------------------------------------------------------------ 单点标注

    /**
     * 标注一个槽位：铺一层全屏透明可触摸层，用户点画面上那个位置，采到就保存。
     *
     * 采点期间游戏收不到点击（正好，标注不该有副作用）。采点层自带「完成」按钮 ——
     * 它盖住了悬浮面板，没有自带退出按钮用户会被困住。
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

        // ★ 顺序很重要：采集层是全屏可触摸的，若加在面板之后就会盖住面板，
        //   用户便无法回去点标注按钮来关闭 —— 所以先摘下面板，加完再放回去。
        panel?.let { runCatching { wm.removeView(it) } }
        runCatching { wm.addView(v, p) }
            .onSuccess {
                pickView = v
                pickSlot = slot
                panel?.let { pnl -> panelParams?.let { pp -> runCatching { wm.addView(pnl, pp) } } }
                val msg = "标注「${Picks.label(slot)}」：点画面上那个位置，再点下方「完成」"
                LogBus.emit(msg)
                flashStatus(msg)
            }
            .onFailure {
                pickView = null
                pickSlot = null
                LogBus.emit("标注层添加失败：${it.message}")
                panel?.let { pnl -> panelParams?.let { pp -> runCatching { wm.addView(pnl, pp) } } }
            }
    }

    /** 结束标注：把采到的点写进对应槽位（没采到就什么都不改）。 */
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
        } else if (p == null) {
            val msg = "⚠ 没采到点，「${Picks.label(slot)}」未改动"
            LogBus.emit(msg)
            flashStatus(msg)
        } else {
            Picks.set(this, slot, p.first, p.second)
            val msg = String.format(
                Locale.US, "✅ %s 已保存 = [%.4f, %.4f]",
                Picks.label(slot), p.first, p.second
            )
            LogBus.emit(msg)
            LogBus.emit("   全部标注：\n" + Picks.describe(this))
            flashStatus(msg)
            refreshMarks()
        }
        ui.post { refreshStatus() }
    }

    // ------------------------------------------------------------ 标点回显

    private fun toggleMarks() {
        val on = !marksOnOf(this)
        setMarksOnOf(this, on)
        marksBtn?.text = marksLabel()
        LogBus.emit(if (on) "标点回显：已开启（只在目标游戏前台时显示）" else "标点回显：已关闭")
        if (!on) removeMarks() else syncMarks()
        ui.post { refreshStatus() }
    }

    /** 回显只在「开关打开 + 目标游戏在前台」时显示 —— 免得在桌面上也糊一层。 */
    private fun syncMarks() {
        val shouldShow = marksOnOf(this) && lastFg == targetPkg
        if (shouldShow && marksView == null) addMarks()
        else if (!shouldShow && marksView != null) removeMarks()
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
            .setContentText("标注坐标与启停任务")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
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
