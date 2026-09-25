package com.altair.probe

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * 视觉地基：设计 token + 共用控件
 * ==============================
 *
 * ## 为什么要有这个文件
 * 改造之前，两个界面里散着几十处 `Color.parseColor("#151A21")` 这样的字面量，同一个角色
 * （卡片底 / 次文字 / 描边）在不同地方值还不一样 —— 改一处配色要全文搜，且一定会漏。
 * 现在颜色、圆角、间距、字号集中在这一处，改配色只动这个文件。
 *
 * ## 只用原生 API
 * `probe/build.gradle.kts` 的 `dependencies {}` 是**刻意留空**的（构建完全离线，
 * 依赖都预热在 `.toolchain/gradle-home`）。加 appcompat / Material 会让离线构建挂掉，
 * 所以圆角用 [GradientDrawable]、按压反馈用 [RippleDrawable]，全部手写，零依赖。
 *
 * ## 为什么悬浮窗用 dp 而不是 sp
 * 覆盖层里的 sp 会跟随系统字体缩放。目标机是 1280x720 横屏，系统字号一旦调大，
 * 悬浮球和面板里的字就会撑破框 —— 那里一律用 `COMPLEX_UNIT_DIP` 显式换算。
 * 主界面是普通 Activity，用 sp 没问题（也跟随用户的字号偏好，是应该的）。
 *
 * ## 状态色只有一个来源
 * 按钮的"选中/危险"、状态灯的颜色，全部走 [paint] 与 [statusColor]。
 * 直接在业务代码里 `background = shape(...)` 会**丢掉水波纹**，
 * 那正是改造前"按钮点下去没反应"的来源 —— 状态变化请用 [paint]。
 */
object Ui {

    // ------------------------------------------------------------ 颜色

    /**
     * 主界面用的**中性黑**配色。
     *
     * 为什么是灰阶而不是带色的深绿：这套工具只在挂机时扫一眼，界面本身没有任何需要
     * 表达的情绪，加了色调反而每个控件都在抢注意力。所以底色到卡片一律是黑灰阶，
     * 颜色只留给**状态**（绿=正常 / 黄=等待 / 红=故障）—— 屏幕上唯一的彩色就是你该看的那一处。
     */
    val BG = Color.parseColor("#0A0A0B")
    val SURFACE = Color.parseColor("#141416")
    val SURFACE_2 = Color.parseColor("#1E1E21")
    val BORDER = Color.parseColor("#2C2C31")

    val TEXT = Color.parseColor("#F3F3F5")
    val TEXT_DIM = Color.parseColor("#A6A6AE")
    val TEXT_FAINT = Color.parseColor("#787880")

    /** 主按钮：亮面。黑底上一块白，比任何彩色都更像"这里是主要动作"。 */
    val PRIMARY = Color.parseColor("#E9E9EC")

    /** 亮面按钮上的文字。 */
    val ON_PRIMARY = Color.parseColor("#0A0A0B")

    val OK = Color.parseColor("#4ADE80")
    val WARN = Color.parseColor("#FBBF24")
    val DANGER = Color.parseColor("#F05252")

    /** 日志区底，比页面底更深，让等宽文字更清楚。 */
    val LOGBG = Color.parseColor("#050506")

    /** 顶栏 / 底部操作条的底。 */
    val BAR = Color.parseColor("#0E0E10")

    // ---- 悬浮面板专用：浮在游戏画面上，必须是**半透明黑** ----
    // 用纯色会像在游戏画面上贴了张纸；半透明既能看清字，又能透出下面的血条/小地图，
    // 挂机时不会因为看不见画面而误判。

    /** 面板底：72% 黑。再透就压不住亮色游戏画面上的文字。 */
    val PANEL_BG = Color.parseColor("#B80D0D0F")

    /** 面板上的普通按钮（半透明白，叠在面板底上）。 */
    val PANEL_CTRL = Color.parseColor("#2EFFFFFF")

    /** 面板上"已生效/已标记"的按钮。 */
    val PANEL_CTRL_ON = Color.parseColor("#59FFFFFF")

    /** 面板描边。 */
    val PANEL_BORDER = Color.parseColor("#38FFFFFF")

    val PANEL_TEXT = Color.parseColor("#F5F5F7")
    val PANEL_TEXT_DIM = Color.parseColor("#B4B4BC")

    /** 按压反馈的白色水波纹（低透明度）。 */
    private val RIPPLE = Color.parseColor("#33FFFFFF")

    // ------------------------------------------------------------ 尺寸（dp / sp）

    const val RADIUS_CARD = 12
    const val RADIUS_CTRL = 8
    const val GAP = 8
    const val PAD_CARD = 14

    /** 最小可点高度。低于这个值在 720p 横屏上很容易点不中。 */
    const val TOUCH_MIN = 44

    private fun scale(ctx: Context) = ctx.resources.displayMetrics.density

    fun dp(ctx: Context, v: Int): Int = (v * scale(ctx)).toInt()

    // ------------------------------------------------------------ 形状与背景

    /** 统一的圆角矩形工厂。[stroke] 为 0 时不画描边。 */
    fun shape(ctx: Context, fill: Int, radiusDp: Int = RADIUS_CTRL, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke != 0) setStroke(dp(ctx, 1), stroke)
        }

    /** 给一个 View 套上圆角底 + 按压水波纹。水波纹是"现代感"最主要的来源。 */
    fun ripple(ctx: Context, base: GradientDrawable, radiusDp: Int = RADIUS_CTRL): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            base,
            shape(ctx, Color.WHITE, radiusDp)
        )

    // ------------------------------------------------------------ 文字

    /** 统一的正文 TextView。 */
    fun text(
        ctx: Context,
        s: CharSequence = "",
        sizeSp: Float = 13f,
        color: Int = TEXT,
        bold: Boolean = false,
        mono: Boolean = false
    ): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        else if (mono) typeface = Typeface.MONOSPACE
    }

    /** 说明文字（灰色小字，通常跟在控件后面解释"为什么"）。 */
    fun doc(ctx: Context, s: String): TextView = text(ctx, s, 12f, TEXT_FAINT).apply {
        setLineSpacing(dp(ctx, 3).toFloat(), 1f)
        setPadding(0, dp(ctx, 8), 0, 0)
    }

    /** 状态圆点。颜色代表状态，实际颜色由调用方按 [statusColor] 更新。 */
    fun dot(ctx: Context, color: Int): TextView = text(ctx, "●", 13f, color, bold = true)

    // ------------------------------------------------------------ 卡片

    /**
     * 圆角卡片。背景**先**上色、再 `setStroke()` —— 反过来描边会被底色盖掉。
     *
     * [trailing] 放在标题行右侧（计数、开关状态之类的短信息），没有就不占位。
     */
    fun card(ctx: Context, title: String?, body: View, trailing: View? = null): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(ctx, SURFACE, RADIUS_CARD, BORDER)
            setPadding(dp(ctx, PAD_CARD), dp(ctx, 12), dp(ctx, PAD_CARD), dp(ctx, PAD_CARD))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }
        if (!title.isNullOrBlank()) {
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(ctx, 8))
            }
            head.addView(
                text(ctx, title, 12f, TEXT_DIM, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (trailing != null) head.addView(trailing)
            box.addView(head)
        }
        box.addView(body)
        return box
    }

    // ------------------------------------------------------------ 按钮

    /** 按钮的四种角色。 */
    enum class Kind { PRIMARY, SECONDARY, GHOST, DANGER }

    /** 角色 → (底色, 文字色, 描边色)。 */
    private fun palette(kind: Kind): Triple<Int, Int, Int> = when (kind) {
        Kind.PRIMARY -> Triple(PRIMARY, ON_PRIMARY, 0)
        Kind.DANGER -> Triple(DANGER, Color.WHITE, 0)
        Kind.SECONDARY -> Triple(SURFACE_2, TEXT, BORDER)
        Kind.GHOST -> Triple(Color.TRANSPARENT, TEXT_DIM, BORDER)
    }

    /**
     * 按角色重绘一颗按钮。
     *
     * ★ 状态切换（"启动任务" ↔ "停止任务"）**必须**走这里，不要自己 `background = shape(...)`：
     * 那样会把 [RippleDrawable] 换成一个静态底，按钮从此失去按压反馈。
     *
     * `tag` 记住上一次的角色，值没变就直接返回 —— 底部操作条是 500ms 刷一次的，
     * 每次都重建两个 Drawable 是纯浪费。
     */
    fun paint(b: Button, kind: Kind, force: Boolean = false) {
        if (!force && b.tag == kind) return
        b.tag = kind
        val (fill, fg, stroke) = palette(kind)
        b.setTextColor(fg)
        b.background = ripple(b.context, shape(b.context, fill, RADIUS_CTRL, stroke), RADIUS_CTRL)
    }

    /**
     * 一个按钮。做法是 `Button` 换掉默认背景 —— 默认背景在深色主题下是浅灰圆角矩形，
     * 和现代化配色完全不搭，这是改造前"看着不现代"的主要原因。
     *
     * ## 宽度
     * `layoutParams` 给的是 `WRAP_CONTENT`，**由父容器决定怎么撑**：
     * [btnRow] 会把它改成等宽平分（水平），[btnFull] 让它整行。
     * 刻意不用"width=0 + weight=1"当默认值 —— 那个组合一旦被放进**垂直**容器，
     * 权重会沿纵轴分配，宽度算出来是 0，按钮直接看不见。
     */
    fun btn(
        ctx: Context,
        label: String,
        kind: Kind = Kind.SECONDARY,
        sizeSp: Float = 13f,
        heightDp: Int = TOUCH_MIN,
        onClick: () -> Unit
    ): Button {
        return Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, heightDp)
            )
            setOnClickListener { onClick() }
            paint(this, kind, force = true)
        }
    }

    /** 整行按钮（占满宽度，比并排的更好点）。 */
    fun btnFull(
        ctx: Context,
        label: String,
        kind: Kind = Kind.PRIMARY,
        sizeSp: Float = 14f,
        heightDp: Int = 46,
        onClick: () -> Unit
    ): Button = btn(ctx, label, kind, sizeSp, heightDp, onClick).also { b ->
        b.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)
        ).apply { bottomMargin = dp(ctx, GAP) }
    }

    /**
     * 并排若干按钮的一行（等宽平分）。
     *
     * 这是**水平**容器，所以这里 `width=0 + weight=1` 成立且正是想要的：
     * 每个按钮平分宽度，无论文字多长都对齐。
     */
    fun btnRow(ctx: Context, vararg btns: Triple<String, Kind, () -> Unit>): LinearLayout {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.forEachIndexed { i, (label, kind, fn) ->
            val b = btn(ctx, label, kind, onClick = fn)
            (b.layoutParams as LinearLayout.LayoutParams).apply {
                width = 0
                weight = 1f
                marginEnd = if (i < btns.size - 1) dp(ctx, 6) else 0
            }
            r.addView(b)
        }
        r.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, GAP) }
        return r
    }

    // ------------------------------------------------------------ 输入行

    /**
     * 统一样式的输入框。
     *
     * 改造前是靠 `EditText.hint` 当标签用 —— 用户一输入标签就没了，回头看不出这格是什么。
     * 现在标签固定在控件左边（[labeledRow]），永远可见。
     *
     * 宽度默认 `MATCH_PARENT`（可以单独放进垂直容器）；[labeledRow] / [checkRow] 会把它
     * 改成 `width=0 + weight=1` 让父容器分配。
     */
    fun input(
        ctx: Context,
        value: String = "",
        hint: String = "",
        numeric: Boolean = false,
        textSize: Float = 14f
    ): EditText = EditText(ctx).apply {
        setText(value)
        this.hint = hint
        setSingleLine(true)
        setSelectAllOnFocus(true)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        setTextColor(TEXT)
        setHintTextColor(TEXT_FAINT)
        background = shape(ctx, LOGBG, RADIUS_CTRL, BORDER)
        setPadding(dp(ctx, 10), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    /** 把输入框改成"由父容器按权重分配宽度" —— 只在水平容器里调用。 */
    private fun flex(input: EditText) {
        input.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    /** "左标签 + 右输入框 + 单位"的一行。 */
    fun labeledRow(
        ctx: Context,
        label: String,
        input: EditText,
        unit: String? = null,
        labelWidthDp: Int = 96
    ): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
        }
        r.addView(text(ctx, label, 13f, TEXT_DIM).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(ctx, labelWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        flex(input)
        r.addView(input)
        if (!unit.isNullOrBlank()) r.addView(text(ctx, "  $unit", 12f, TEXT_FAINT))
        r.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 6) }
        return r
    }

    /** 统一样式的复选框（默认方块在深色底上几乎看不见，这里指定主色）。 */
    fun check(ctx: Context, label: String, checked: Boolean): CheckBox = CheckBox(ctx).apply {
        text = label
        isChecked = checked
        setTextColor(TEXT)
        textSize = 14f
        buttonTintList = ColorStateList.valueOf(PRIMARY)
        setPadding(0, 0, dp(ctx, 6), 0)
        layoutParams = LinearLayout.LayoutParams(
            0, dp(ctx, TOUCH_MIN), 1f
        )
    }

    /** "复选框 + 数值输入 + 单位"的一行（技能间隔用）。 */
    fun checkRow(ctx: Context, box: CheckBox, input: EditText, unit: String = "秒", inputWidthDp: Int = 84): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        r.addView(box)
        input.layoutParams = LinearLayout.LayoutParams(dp(ctx, inputWidthDp), dp(ctx, TOUCH_MIN - 4))
        input.gravity = Gravity.CENTER
        r.addView(input)
        r.addView(text(ctx, "  $unit", 12f, TEXT_DIM))
        r.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 4) }
        return r
    }

    // ------------------------------------------------------------ 其它

    /** 1dp 分隔线。[color] 默认主界面的描边色；悬浮面板要传半透明白，否则在深色面板上看不见。 */
    fun divider(
        ctx: Context,
        topMarginDp: Int = 5,
        bottomMarginDp: Int = 5,
        color: Int = BORDER
    ): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)
        ).apply { topMargin = dp(ctx, topMarginDp); bottomMargin = dp(ctx, bottomMarginDp) }
        setBackgroundColor(color)
    }

    /**
     * 毫秒倒计时 → `MM:SS`。
     *
     * 悬浮球和面板摘要都需要短格式。未运行 / 已到点分别用 `--:--` 和 `00:00` 表示。
     */
    fun mmss(remainMs: Long, running: Boolean): String {
        if (!running) return "--:--"
        val ms = remainMs.coerceAtLeast(0L)
        val total = ms / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    /** 状态色：引擎状态 + 前台是否为目标游戏 → 一个颜色。 */
    fun statusColor(running: Boolean, error: Boolean, armed: Boolean): Int = when {
        error -> DANGER
        !running -> TEXT_DIM
        armed -> OK
        else -> WARN
    }
}
