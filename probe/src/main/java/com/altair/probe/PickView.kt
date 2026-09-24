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
 *   - 点一下，就记录下那个位置的归一化坐标，并画一个带编号的标记
 *   - 游戏收不到这次点击（正好，标注时不该产生副作用）
 *
 * 一次只标**一个**槽位（技能1..4 / 跳跃 / 轮盘），采到就由 [OverlayService] 存进 [Picks]。
 */
class PickView(ctx: Context, private val hint: String = "") : View(ctx) {

    /** 已采集的归一化坐标 (x, y)，取值 0~1。 */
    val points = mutableListOf<Pair<Float, Float>>()

    /** 每采到一个点回调：(序号从1开始, nx, ny)。 */
    var onPick: ((Int, Float, Float) -> Unit)? = null

    /** 点「完成采点」时的回调 —— 用于退出采集模式。 */
    var onFinish: (() -> Unit)? = null

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

    // ---- 内置「完成采点」按钮 ----
    // 为什么必须在采集层里自带一个退出按钮：采集层是全屏可触摸的，
    // 会盖住悬浮面板，用户就没法回去点面板上的「★采点」来关闭了。
    private val finishRect = RectF()
    private var finishPressed = false
    private val finishBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E62563EB")
    }
    private val finishEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val finishText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishPressed = finishRect.contains(e.x, e.y)
                if (finishPressed) invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (finishPressed && !finishRect.contains(e.x, e.y)) {
                    finishPressed = false
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (finishPressed) {
                    finishPressed = false
                    invalidate()
                    onFinish?.invoke()      // 点的是「完成」按钮 → 退出采集，不记录坐标
                    return true
                }
                if (width <= 0 || height <= 0) return true
                val nx = (e.x / width).coerceIn(0f, 1f)
                val ny = (e.y / height).coerceIn(0f, 1f)
                points.add(nx to ny)
                onPick?.invoke(points.size, nx, ny)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    fun clearAll() {
        points.clear()
        invalidate()
    }

    /** 导出成便于粘贴的文本。 */
    fun export(prefix: String = "点"): String {
        if (points.isEmpty()) return "（还没有采集任何坐标）"
        return points.mapIndexed { i, (x, y) ->
            "%s%d = [%.4f, %.4f]".format(prefix, i + 1, x, y)
        }.joinToString("\n")
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

        points.forEachIndexed { i, (nx, ny) ->
            val cx = nx * w
            val cy = ny * h
            val r = 30f
            canvas.drawCircle(cx, cy, r + 3f, halo)
            canvas.drawCircle(cx, cy, r, cross)
            canvas.drawLine(cx - r - 14f, cy, cx + r + 14f, cy, halo)
            canvas.drawLine(cx - r - 14f, cy, cx + r + 14f, cy, cross)
            canvas.drawLine(cx, cy - r - 14f, cx, cy + r + 14f, halo)
            canvas.drawLine(cx, cy - r - 14f, cx, cy + r + 14f, cross)
            // 编号（带黑底描边，保证在任何画面上都看得清）
            val n = "${i + 1}"
            canvas.drawCircle(cx + r + 22f, cy - r - 22f, 20f, dot)
            canvas.drawText(n, cx + r + 22f - (if (n.length > 1) 16f else 9f), cy - r - 11f, text)
        }

        drawHint(canvas, w)
        drawFinishButton(canvas, w, h)
    }

    /** 顶部提示条：明确告诉用户"现在标的是哪一项"，避免标错槽位。 */
    private fun drawHint(canvas: Canvas, w: Float) {
        if (hint.isBlank()) return
        val tw = text.measureText(hint)
        val boxW = tw + 40f
        val left = (w - boxW) / 2f
        finishBg.color = Color.parseColor("#E6000000")
        canvas.drawRoundRect(
            android.graphics.RectF(left, 24f, left + boxW, 24f + 62f), 16f, 16f, finishBg
        )
        text.color = Color.WHITE
        canvas.drawText(hint, left + 20f, 24f + 44f, text)
    }

    private fun drawFinishButton(canvas: Canvas, w: Float, h: Float) {
        val bw = 380f
        val bh = 104f
        val margin = 56f
        finishRect.set(
            (w - bw) / 2f, h - bh - margin,
            (w - bw) / 2f + bw, h - margin
        )
        finishBg.color = if (finishPressed) Color.parseColor("#E63B82F6") else Color.parseColor("#E62563EB")
        canvas.drawRoundRect(finishRect, 22f, 22f, finishBg)
        canvas.drawRoundRect(finishRect, 22f, 22f, finishEdge)
        val label = if (points.isEmpty()) "完成标注（还没点）" else "完成标注（已点 ${points.size} 个）"
        val cy = finishRect.centerY() - (finishText.descent() + finishText.ascent()) / 2f
        canvas.drawText(label, finishRect.centerX(), cy, finishText)
    }
}
