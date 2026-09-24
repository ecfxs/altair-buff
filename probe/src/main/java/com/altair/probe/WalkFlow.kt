package com.altair.probe

import android.content.Context

/**
 * 原地走位（防挂机检测）
 * ====================
 *
 * 用户指定的走法 —— 1:2:1 三段 + 原地跳一下：
 * ```
 *   ① 往左走 D 毫秒
 *   ② 往右走 2D 毫秒     ← 双倍：越过原点再往右
 *   ③ 往左走 D 毫秒       ← 回到原点
 *   ④ 原地跳一下
 * ```
 * 三段都从**轮盘中心**推杆（左推 / 右推 / 左推），1:2:1 的配比决定了走完必然回到起点附近，
 * 所以叫"原地"走位。D 在「设置」页可调，默认 600ms。
 *
 * ## 为什么是"时长驱动"而不是"走回原点"
 * 曾经用血条 x 坐标做闭环把角色对齐回原点，但那要求每步截一次图，而截图与按键抢同一个
 * 常驻 root shell —— 实机日志里它把按键堵成 6 秒一次。现在**不截任何图**，
 * 纯时长驱动；前提是轮盘推杆在游戏里真的能持续移动（这条已实机验证）。
 *
 * ## 走位通道
 * 只走轮盘，不走方向键。原因：键盘通道在野外实测对数字键无响应，
 * 而轮盘（左下角摇杆）是游戏自己的移动入口，最可靠。整块键盘通道随之删除。
 */
object WalkFlow {

    private const val PREF = "walk"

    @Volatile private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    private fun prefs() = ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 参数

    /** 单程时长 D（毫秒）。一次走位总时长 ≈ 4D。 */
    var legMs: Long
        get() = prefs()?.getLong("legMs", 600L)?.coerceIn(100L, 10_000L) ?: 600L
        set(v) {
            prefs()?.edit()?.putLong("legMs", v.coerceIn(100L, 10_000L))?.apply()
        }

    /** 走位间隔（分钟），默认 15（用户要求）。 */
    var intervalMin: Long
        get() = prefs()?.getLong("intervalMin", 15L)?.coerceIn(1L, 24L * 60L) ?: 15L
        set(v) {
            prefs()?.edit()?.putLong("intervalMin", v.coerceIn(1L, 24L * 60L))?.apply()
        }

    /** 走位间隔（毫秒）。 */
    val intervalMs: Long get() = intervalMin * 60_000L

    /** 推杆幅度（屏幕宽度的百分比），默认 6%。越大走得越快、也越容易走过头。 */
    var pushPct: Int
        get() = prefs()?.getInt("pushPct", 6)?.coerceIn(1, 30) ?: 6
        set(v) {
            prefs()?.edit()?.putInt("pushPct", v.coerceIn(1, 30))?.apply()
        }

    /** 跳跃按压时长（毫秒），默认 90。 */
    var jumpPressMs: Int
        get() = prefs()?.getInt("jumpPressMs", Picks.TAP_PRESS_MS)?.coerceIn(30, 600)
            ?: Picks.TAP_PRESS_MS
        set(v) {
            prefs()?.edit()?.putInt("jumpPressMs", v.coerceIn(30, 600))?.apply()
        }

    /** 两段走位之间的停顿：让摇杆手势彻底结束、角色站稳，再走下一段。 */
    private const val LEG_PAUSE_MS = 250L

    /** 走完到跳之间的停顿。 */
    private const val JUMP_SETTLE_MS = 450L

    // ------------------------------------------------------------ 主流程

    /**
     * 走一次原地走位：左 D → 右 2D → 左 D → 跳一下。
     *
     * 返回 (是否成功, 说明)。**只要三段走位发出去了就算成功**；
     * 跳跃没标注时不算失败（只是不跳），因为"走位"本身已经完成。
     */
    fun strollAndJump(log: (String) -> Unit): Pair<Boolean, String> {
        val c = ctx ?: return false to "未初始化"
        val joy = Picks.joystick(c)
        val cx = joy.first.toDouble()
        val cy = joy.second.toDouble()
        val off = pushPct / 100.0
        val leftX = (cx - off).coerceIn(0.02, 0.98)
        val rightX = (cx + off).coerceIn(0.02, 0.98)
        val d = legMs

        if (!Picks.joystickAnnotated(c)) {
            log("  ⚠ 轮盘中心未标注，先用默认位置 (%.3f, %.3f) —— 建议切到游戏点悬浮窗「标轮盘」"
                .format(cx, cy))
        }
        log("  走位参数：单程 ${d}ms，推杆 ${pushPct}%（轮盘中心 %.3f, %.3f）".format(cx, cy))

        log("  ① 轮盘左推 ${d}ms")
        push(cx, cy, leftX, cy, d, "左", log)
        sleep(LEG_PAUSE_MS)

        log("  ② 轮盘右推 ${2 * d}ms（双倍）")
        push(cx, cy, rightX, cy, d * 2, "右", log)
        sleep(LEG_PAUSE_MS)

        log("  ③ 轮盘左推 ${d}ms（回原点）")
        push(cx, cy, leftX, cy, d, "左", log)
        sleep(JUMP_SETTLE_MS)

        val jump = Picks.get(c, Picks.JUMP)
        if (jump == null) {
            val m = "走位完成（左 ${d} → 右 ${2 * d} → 左 ${d}）；跳跃已跳过：" +
                "「跳跃」还没标注，切到游戏点悬浮窗「标跳跃」"
            log("  ④ $m")
            return true to m
        }
        log("  ④ 原地跳一下")
        val r = Picks.tapPoint(jump, "跳跃")
        val msg = if (r.first) {
            "走位完成：左 ${d} → 右 ${2 * d} → 左 ${d} → 跳（${jumpPressMs}ms）"
        } else {
            "走位完成但跳跃点击失败：${r.second.take(80)}"
        }
        return r.first to msg
    }

    /**
     * 推一次轮盘。
     *
     * 用 `joystickWalk` 的默认通道：优先 `input motionevent`（按下 → 拖到偏移 → 保持 → 松手），
     * 这是摇杆语义上正确的表达 —— 角色在**整个 holdMs 期间**都处于满推杆状态，
     * 所以"走多久"和"走多远"大致成正比。该机型不支持 motionevent 时它会自动退回 swipe。
     */
    private fun push(
        cx: Double, cy: Double,
        mx: Double, my: Double,
        ms: Long,
        side: String,
        log: (String) -> Unit,
    ) {
        val r = runCatching {
            ShellCore.probe.joystickWalk(cx, cy, mx, my, ms.toInt())
        }.getOrElse { "摇杆调用异常：${it.javaClass.simpleName}: ${it.message}" }
        log("     轮盘$side：$r")
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    // ------------------------------------------------------------ 展示

    /** 一行参数摘要，供「设置」页与日志展示。 */
    fun describe(c: Context): String {
        val joy = Picks.get(c, Picks.JOYSTICK)
        val joyText = if (joy == null) {
            "未标注（用默认左下角）"
        } else {
            "[%.4f, %.4f]".format(joy.first, joy.second)
        }
        return "原地走位：左 ${legMs}ms → 右 ${legMs * 2}ms → 左 ${legMs}ms → 跳（${jumpPressMs}ms）\n" +
            "走位间隔：每 ${intervalMin} 分钟一次\n" +
            "推杆幅度：${pushPct}%   轮盘中心：$joyText"
    }
}
