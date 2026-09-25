package com.altair.probe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * 坐标标注层
 * ==========
 *
 * ## 为什么需要它
 * 触摸点击必须知道**准确的按钮坐标**。从截图推算坐标既慢又不准（技能键是半透明圆角，
 * 自动检测效果很差），而自动识别一旦偏了就会点到游戏里别的地方。最准的办法极其简单：
 * **让用户直接在游戏画面上点一下**。
 *
 * 这一层就是干这个的 —— 全屏透明、可触摸：
 *   - 用户看到的是游戏画面（底下透出来）
 *   - 点一下，就记录下那个位置的归一化坐标，画一个十字标记，然后由 [OverlayService] 落盘
 *   - 游戏收不到这次点击（正好，标注时不该产生副作用）
 *
 * ## 为什么自带一个「取消」按钮
 * 采点层是全屏可触摸的，会盖住悬浮面板，用户没法回去点面板上的按钮退出。
 * 所以退出路径必须长在这一层上。
 */
class PickView(ctx: Context, private val hint: String = "") : View(ctx) {

    /** 已采集的归一化坐标 (x, y)，取值 0~1。 */
    val points = mutableListOf<Pair<Float, Float>>()

    /** 每采到一个点回调：(序号从1开始, nx, ny)。 */
    var onPick: ((Int, Float, Float) -> Unit)? = null

    /** 点「取消」时的回调 —— 用于退出采集模式。 */
    var onFinish: (() -> Unit)? = null

    // ---- 画点用的笔 ----
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#FFFFC53D")
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#CC000000")
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFC53D")
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        typeface = Typeface.MONOSPACE
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#33FFFFFF")
    }

    // ---- 顶部提示条 / 底部取消按钮（各用各的笔，避免配色互相污染）----
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6000000")
    }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val cancelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cancelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#66FFFFFF")
    }
    private val cancelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val cancelRect = RectF()
    private var cancelPressed = false

    init {
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** 取消按钮的矩形在尺寸确定时算一次，供 [onTouchEvent] 做命中判定。 */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bw = minOf(w * 0.42f, 380f)
        val bh = 104f
        val margin = 48f
        cancelRect.set((w - bw) / 2f, h - bh - margin, (w + bw) / 2f, h - margin)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPressed = cancelRect.contains(e.x, e.y)
                if (cancelPressed) invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (cancelPressed && !cancelRect.contains(e.x, e.y)) {
                    cancelPressed = false
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (cancelPressed) {
                    cancelPressed = false
                    invalidate()
                    onFinish?.invoke()      // 点的是「取消」→ 退出采集，不记录坐标
                    return true
                }
                // 一个槽位只采一个点：已经采到了就忽略后续点击，等 [OverlayService] 摘掉这一层。
                if (width <= 0 || height <= 0 || points.isNotEmpty()) return true
                val screen = ScreenGeometry.read(context)
                val nx = (e.rawX / screen.width).coerceIn(0f, 1f)
                val ny = (e.rawY / screen.height).coerceIn(0f, 1f)
                points.add(nx to ny)
                onPick?.invoke(points.size, nx, ny)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1/10 网格，方便肉眼读数
        for (i in 1..9) {
            canvas.drawLine(w * i / 10f, 0f, w * i / 10f, h, guide)
            canvas.drawLine(0f, h * i / 10f, w, h * i / 10f, guide)
        }

        val screen = ScreenGeometry.read(context)
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        points.forEachIndexed { i, (nx, ny) ->
            val cx = nx * screen.width - origin[0]
            val cy = ny * screen.height - origin[1]
            val r = 30f
            canvas.drawCircle(cx, cy, r + 3f, halo)
            canvas.drawCircle(cx, cy, r, cross)
            canvas.drawLine(cx - r - 14f, cy, cx + r + 14f, cy, halo)
            canvas.drawLine(cx - r - 14f, cy, cx + r + 14f, cy, cross)
            canvas.drawLine(cx, cy - r - 14f, cx, cy + r + 14f, halo)
            canvas.drawLine(cx, cy - r - 14f, cx, cy + r + 14f, cross)
            // 编号（带底色圆点，保证在任何画面上都看得清）
            val n = "${i + 1}"
            canvas.drawCircle(cx + r + 22f, cy - r - 22f, 20f, dot)
            canvas.drawText(n, cx + r + 22f - (if (n.length > 1) 16f else 9f), cy - r - 11f, text)
        }

        drawHint(canvas, w)
        drawCancelButton(canvas)
    }

    /** 顶部提示条：明确告诉用户"现在标的是哪一项"，避免标错槽位。 */
    private fun drawHint(canvas: Canvas, w: Float) {
        if (hint.isBlank()) return
        val tw = hintText.measureText(hint)
        val boxW = minOf(tw + 56f, w - 32f)
        val left = (w - boxW) / 2f
        canvas.drawRoundRect(RectF(left, 20f, left + boxW, 96f), 20f, 20f, hintBg)
        val baseline = 20f + 48f - (hintText.descent() + hintText.ascent()) / 2f
        canvas.drawText(hint, w / 2f, baseline, hintText)
    }

    private fun drawCancelButton(canvas: Canvas) {
        cancelBg.color = if (cancelPressed) Color.parseColor("#E6B4453C") else Color.parseColor("#B3151A21")
        canvas.drawRoundRect(cancelRect, 22f, 22f, cancelBg)
        canvas.drawRoundRect(cancelRect, 22f, 22f, cancelEdge)
        val cy = cancelRect.centerY() - (cancelText.descent() + cancelText.ascent()) / 2f
        canvas.drawText("取消标注", cancelRect.centerX(), cy, cancelText)
    }
}
