package com.altair.probe

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
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
 * 悬浮球和面板里的字就会撑破框 —— 那里一律用 [dpText] 显式换算成像素。
 * 主界面是普通 Activity，用 sp 没问题（也跟随用户的字号偏好，是应该的）。
 */
object Ui {

    // ------------------------------------------------------------ 颜色

    /** 页面底色。接近黑但不是死黑，比原来的 #0F1216 有层次。 */
    val BG = Color.parseColor("#12161C")

    /** 卡片面。比底色亮一档，用来分层。 */
    val SURFACE = Color.parseColor("#1A2029")

    /** 输入框底、次级按钮底。 */
    val SURFACE_2 = Color.parseColor("#232B36")

    /** 描边。 */
    val BORDER = Color.parseColor("#232B36")

    /** 主文字。 */
    val TEXT = Color.parseColor("#E6EDF5")

    /** 次文字（标签、次要信息）。 */
    val TEXT_DIM = Color.parseColor("#94A3B8")

    /** 说明文字（最弱的一级）。 */
    val TEXT_FAINT = Color.parseColor("#64748B")

    /** 主色（选中、主要动作）。 */
    val PRIMARY = Color.parseColor("#3B82F6")

    val OK = Color.parseColor("#22C55E")
    val WARN = Color.parseColor("#F59E0B")
    val DANGER = Color.parseColor("#EF4444")

    /** 日志区底，比页面底更深，让等宽文字更清楚。 */
    val LOGBG = Color.parseColor("#0B0E12")

    /** 页签条、状态条的底。 */
    val BAR = Color.parseColor("#151A21")

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

    /**
     * dp 当字号用。
     *
     * `TypedValue.COMPLEX_UNIT_DIP` 在 `setTextSize` 里就是"按密度换算、不跟随系统字号"，
     * 正是悬浮窗需要的。用这个而不是自己乘 —— 免得缩放逻辑和系统不一致。
     */
    fun dpText(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v,
        android.content.res.Resources.getSystem().displayMetrics
    )

    // ------------------------------------------------------------ 形状与背景

    /** 统一的圆角矩形工厂。[stroke] 为 0 时不画描边。 */
    fun shape(ctx: Context, fill: Int, radiusDp: Int = RADIUS_CTRL, stroke: Int = 0, strokeDp: Int = 1)
            : GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(ctx, strokeDp), stroke)
    }

    /** 左边圆角、右边直角（页签用）。 */
    fun shapeCorners(
        ctx: Context, fill: Int, radiusDp: Int, stroke: Int = 0,
        tl: Boolean = true, tr: Boolean = true, br: Boolean = true, bl: Boolean = true
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        val r = dp(ctx, radiusDp).toFloat()
        cornerRadii = floatArrayOf(
            if (tl) r else 0f, if (tl) r else 0f,
            if (tr) r else 0f, if (tr) r else 0f,
            if (br) r else 0f, if (br) r else 0f,
            if (bl) r else 0f, if (bl) r else 0f
        )
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

    /** 小节标题。 */
    fun section(ctx: Context, title: String): TextView = text(ctx, title, 11f, TEXT_DIM, bold = true).apply {
        setPadding(0, dp(ctx, 16), 0, dp(ctx, 6))
    }

    /** 说明文字（灰色小字，通常跟在控件后面解释"为什么"). */
    fun doc(ctx: Context, s: String): TextView = text(ctx, s, 10.5f, TEXT_FAINT).apply {
        setLineSpacing(dp(ctx, 3).toFloat(), 1f)
        setPadding(0, dp(ctx, 8), 0, 0)
    }

    // ------------------------------------------------------------ 卡片

    /**
     * 圆角卡片。背景**先**上色、再 `setStroke()` —— 反过来描边会被底色盖掉。
     */
    fun card(ctx: Context, title: String?, body: View): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(ctx, SURFACE, RADIUS_CARD, BORDER)
            setPadding(dp(ctx, PAD_CARD), dp(ctx, 12), dp(ctx, PAD_CARD), dp(ctx, PAD_CARD))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }
        if (!title.isNullOrBlank()) {
            box.addView(text(ctx, title, 11f, TEXT_DIM).apply {
                setPadding(0, 0, 0, dp(ctx, 8))
            })
        }
        box.addView(body)
        return box
    }

    // ------------------------------------------------------------ 按钮

    /** 按钮的四种角色。 */
    enum class Kind { PRIMARY, SECONDARY, GHOST, DANGER }

    /**
     * 一个按钮。做法是 `Button` 换掉默认背景 —— 默认背景在深色主题下是浅灰圆角矩形，
     * 和现代化配色完全不搭，这是改造前"看着不现代"的主要原因。
     *
     * ## 宽度
     * `layoutParams` 给的是 `WRAP_CONTENT`，**由父容器决定怎么撑**：
     * [btnRow] 会把它改成等宽平分（水平），[btn] 单独用则自适应内容。
     * 刻意不用"width=0 + weight=1"当默认值 —— 那个组合一旦被放进**垂直**容器，
     * 权重会沿纵轴分配，宽度算出来是 0，按钮直接看不见。
     */
    fun btn(
        ctx: Context,
        label: String,
        kind: Kind = Kind.SECONDARY,
        sizeSp: Float = 12f,
        heightDp: Int = TOUCH_MIN,
        onClick: () -> Unit
    ): Button {
        val (fill, fg, stroke) = when (kind) {
            Kind.PRIMARY -> Triple(PRIMARY, Color.WHITE, 0)
            Kind.DANGER -> Triple(DANGER, Color.WHITE, 0)
            Kind.SECONDARY -> Triple(SURFACE_2, TEXT, BORDER)
            Kind.GHOST -> Triple(Color.TRANSPARENT, TEXT_DIM, BORDER)
        }
        return Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(fg)
            background = ripple(ctx, shape(ctx, fill, RADIUS_CTRL, stroke), RADIUS_CTRL)
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(ctx, heightDp)
            )
            setOnClickListener { onClick() }
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
    ): LinearLayout = btnRow(ctx, Triple(label, kind, onClick)).also { row ->
        // 单按钮整行：给它全部宽度
        (row.getChildAt(0).layoutParams as LinearLayout.LayoutParams).let {
            it.width = LinearLayout.LayoutParams.MATCH_PARENT
            it.weight = 0f
        }
        (row.getChildAt(0) as Button).setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        row.getChildAt(0).layoutParams.height = dp(ctx, heightDp)
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

    /**
     * 一行"标签 + 可折叠"的标题条。点一下展开/收起。
     *
     * 返回 (标题条, 内容容器)。调用方自行往容器里加内容，并决定初始是否可见。
     */
    fun collapsible(ctx: Context, title: String): Pair<TextView, LinearLayout> {
        val head = text(ctx, "▸ $title", 12f, TEXT_DIM, bold = true).apply {
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 8))
            isClickable = true
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        head.setOnClickListener {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            head.text = (if (show) "▾ " else "▸ ") + title
        }
        return head to body
    }

    // ------------------------------------------------------------ 输入行

    /**
     * "左标签 + 右输入框 + 单位"的一行。
     *
     * 改造前是靠 `EditText.hint` 当标签用 —— 用户一输入标签就没了，回头看不出这格是什么。
     * 现在标签固定在左边，永远可见。
     */
    fun labeledRow(
        ctx: Context,
        label: String,
        input: EditText,
        unit: String? = null,
        labelWidthDp: Int = 92
    ): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
        }
        r.addView(text(ctx, label, 12f, TEXT_DIM).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, labelWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        input.setTextColor(TEXT)
        input.setHintTextColor(TEXT_FAINT)
        input.background = shape(ctx, LOGBG, RADIUS_CTRL, BORDER)
        input.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        input.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        r.addView(input)
        if (!unit.isNullOrBlank()) {
            r.addView(text(ctx, "  $unit", 11f, TEXT_FAINT))
        }
        r.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 6) }
        return r
    }

    /** 状态徽章：一个圆点 + 一行字。 */
    fun badge(ctx: Context, dotColor: Int, s: String, sizeSp: Float = 13f): TextView =
        text(ctx, "● $s", sizeSp, dotColor, bold = true)

    // ------------------------------------------------------------ 其它

    /** 1dp 分隔线。 */
    fun divider(ctx: Context, topMarginDp: Int = 5, bottomMarginDp: Int = 5): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)
        ).apply { topMargin = dp(ctx, topMarginDp); bottomMargin = dp(ctx, bottomMarginDp) }
        setBackgroundColor(BORDER)
    }

    /**
     * 毫秒倒计时 → `MM:SS`。
     *
     * 悬浮球和面板摘要都需要短格式。`Engine.countdown()` 返回的是 `"4分12秒后"` 这种中文串，
     * 球上放不下，所以这里单独算。未运行 / 已到点分别用 `--:--` 和 `00:00` 表示。
     */
    fun mmss(remainMs: Long, running: Boolean): String {
        if (!running) return "--:--"
        val ms = remainMs.coerceAtLeast(0L)
        val total = ms / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    /** 状态色：引擎状态 + 前台是否为目标游戏 → 一个颜色。 */
    fun statusColor(running: Boolean, error: Boolean, armed: Boolean): Int = when {
        !running -> TEXT_DIM
        error -> DANGER
        armed -> OK
        else -> WARN
    }
}
