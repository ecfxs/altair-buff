package com.altair.probe

import android.content.Context

/**
 * 技能带（skill bar）与 1-4 号技能位
 * ================================
 *
 * 目标：**不再要求手动采点**，开箱就知道 4 个技能在哪，并且坐标是"固定"的。
 *
 * ## 坐标从哪来
 * 不是推算的，是实机采点实测值（`config/rules.example.json` 的 v0.13 标定产物）：
 *
 * ```
 * skillBar.bandRoi        = [0.715, 0.535, 0.95, 0.615]
 * skillBar.columnPeriodPx = 74                       ← 相邻技能列的像素间距
 * skill_1 = [0.7416, 0.5673]   skill_2 = [0.8041, 0.5756]
 * skill_3 = [0.8649, 0.5673]   skill_4 = [0.918, 0.5728]
 * ```
 *
 * 标定笔记里还留了一条教训：**"此前推算的 x=0.7250/0.7594/0.7937/0.8280 偏左约 0.06"** ——
 * 也就是"按等距均分带子"这种想当然的推算会偏半格多。所以这里：
 *   · 默认值直接用实测值（推算靠不住）
 *   · 自动识别只在实测值附近做**小幅吸附**，绝不重排
 *
 * ## "自动识别"只做到哪一步（有意的克制）
 * 带内按 74px 周期做相位搜索，其实能稳定找出一个相位 —— 但**那个相位是按钮"边缘"**（边缘梯度最强处），
 * 而采点采的是按钮"中心"，两者差约半个按钮宽。实测三张截图都指向 -21px，正是边缘相位。
 * 所以：**不自动应用这个位移**。把它当诊断信息报告出来，坐标一律用实测值。
 * （宁可不动，也不能把点从按钮中心推到边框上 —— 那会点不准，而且没人看得出来为什么。）
 *
 * 若内置坐标在你的机型上确实不落在技能上：悬浮窗开「ROI显示」，
 * 图上会画出 4 个采集点的位置与编号，照着把偏差告诉我，或者直接重新采一次点。
 */
object SkillBar {

    /** 技能带（归一化 ROI）。 */
    val BAND = floatArrayOf(0.715f, 0.535f, 0.95f, 0.615f)

    /** 相邻技能列间距（像素，基于 1280 宽实测）。 */
    const val COLUMN_PERIOD_PX = 74

    /**
     * 实测默认坐标（归一化）。顺序 = 技能 1..4。
     * 这是"固定坐标位置"的来源：装好就有值，不需要采点。
     */
    val DEFAULT = listOf(
        0.7416f to 0.5673f,
        0.8041f to 0.5756f,
        0.8649f to 0.5673f,
        0.9180f to 0.5728f,
    )

    /** 吸附的最大允许位移：半格。超过半格说明不是同一排技能，宁可不吸。 */
    private const val MAX_SNAP = 0.5

    /**
     * 保证技能位已有值：采集点不足 4 个时，用实测默认值补齐前 4 个（技能位）。
     *
     * 为什么放在这里而不是让用户采点：技能位置在 UI 上是固定的，装好就已知；
     * 让用户为固定 UI 手工采点既多余又容易采歪（历史上就采偏过）。
     *
     * @return 是否写入了默认值
     */
    fun ensureDefaults(ctx: Context): Boolean {
        val pts = OverlayService.pickedPointsOf(ctx)
        if (pts.size >= 4) return false
        // 保留已有的前几个点（用户可能已经采过菜单/自由市场等），只补齐缺的技能位
        val merged = ArrayList<Pair<Float, Float>>(8)
        for (i in 0 until 4) {
            merged.add(pts.getOrNull(i) ?: DEFAULT[i])
        }
        for (i in 4 until pts.size) merged.add(pts[i])
        OverlayService.savePickedPointsOf(ctx, merged)
        return true
    }

    /**
     * 「技能位」按钮：把技能 1-4 **固定成实测坐标**，并把带内相位观测值报告出来（仅诊断，不应用）。
     *
     * 为什么不应用相位位移：见类注释 —— 那个相位是按钮边缘、不是中心，应用了反而点偏。
     *
     * @return (是否成功, 说明)
     */
    fun resetToMeasured(ctx: Context, log: (String) -> Unit): Pair<Boolean, String> {
        val rest = OverlayService.pickedPointsOf(ctx).drop(4)
        OverlayService.savePickedPointsOf(ctx, DEFAULT + rest)

        val shift = runCatching { detectShiftPx() }.getOrNull()
        if (shift == null) {
            log("带内相位观测不可用（画面被遮挡或信号弱）")
        } else {
            val px = COLUMN_PERIOD_PX
            log("带内观测：技能排可能整体偏移 ${shift}px（一个技能列 = ${px}px）")
            log("  ⚠ 该相位对应的是按钮**边缘**，与采点的按钮**中心**相差约半格，因此不自动应用")
        }
        return true to DEFAULT.joinToString(" ") { "%.4f,%.4f".format(it.first, it.second) }
    }

    /**
     * 在技能带里找"整排技能"相对默认位置的整体水平偏移（像素）。
     *
     * 做法：算带内逐列竖向边缘强度，按 [COLUMN_PERIOD_PX] 的周期在 ±半格内做相位搜索，
     * 取与默认排布（即相位 0）相比能量最高的那个偏移。
     */
    private fun detectShiftPx(): Int? {
        val raw = ShellCore.probe.captureRawForVision() ?: return null
        val (w, h, rgb) = raw
        val x1 = (BAND[0] * w).toInt().coerceIn(0, w - 1)
        val x2 = (BAND[2] * w).toInt().coerceIn(x1 + 1, w)
        val y1 = (BAND[1] * h).toInt().coerceIn(0, h - 1)
        val y2 = (BAND[3] * h).toInt().coerceIn(y1 + 1, h)
        val bw = x2 - x1

        // 逐列竖向边缘强度（相邻像素亮度差）
        val col = DoubleArray(bw)
        for (yy in y1 until y2) {
            var prev = lum(rgb[yy * w + x1])
            for (i in 1 until bw) {
                val cur = lum(rgb[yy * w + x1 + i])
                col[i] += kotlin.math.abs(cur - prev)
                prev = cur
            }
        }
        val maxPx = (COLUMN_PERIOD_PX * MAX_SNAP).toInt()
        var bestShift = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var zeroScore = 0.0
        // 默认排布在带内的相位：技能 1 相对带左沿的位置
        val basePhase = ((DEFAULT[0].first * w) - x1).toInt()
        for (dx in -maxPx..maxPx) {
            var s = 0.0
            for (k in 0 until 4) {
                val px = basePhase + dx + k * COLUMN_PERIOD_PX
                if (px in 1 until bw) s += col[px]
            }
            if (dx == 0) zeroScore = s
            if (s > bestScore) { bestScore = s; bestShift = dx }
        }
        // 信号不够强就认为没偏移，别乱吸
        if (bestScore <= 0.0 || zeroScore <= 0.0) return null
        return bestShift
    }

    private fun lum(rgb: Int): Double {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    fun describe(ctx: Context): String {
        val pts = OverlayService.pickedPointsOf(ctx).take(4)
        if (pts.isEmpty()) return "技能位未初始化"
        return pts.mapIndexed { i, p -> "${i + 1}:(%.4f,%.4f)".format(p.first, p.second) }.joinToString(" ")
    }
}
