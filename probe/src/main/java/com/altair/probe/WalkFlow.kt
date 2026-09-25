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

    /** 让路矩形外扩的像素数（见 [walkRectPx]）。 */
    private const val WALK_RECT_PAD = 48

    // ------------------------------------------------------------ 主流程

    /**
     * 走一次原地走位：左 D → 右 2D → 左 D → 跳一下。
     *
     * 返回 (是否成功, 说明)。**只要三段走位发出去了就算成功**；
     * 跳跃没标注时不算失败（只是不跳），因为"走位"本身已经完成。
     *
     * 整段（4 段手势 + 跳）都包在 [InjectShield.aroundWalk] 里：轮盘在左下角，而展开后的
     * 面板能盖住半个屏幕，注入会被面板自己吃掉（点「试走位」→ 面板收起成球、角色不动）。
     * 让路必须在**整段**外面开一次，否则每段手势都要重新挪一次窗口。
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

        val r = walkRectPx(c, cx, cy, leftX, rightX)
        return InjectShield.aroundWalk(r[0], r[1], r[2], r[3]) {
            stroll(c, cx, cy, leftX, rightX, d, log)
        }
    }

    private fun stroll(
        c: Context,
        cx: Double, cy: Double,
        leftX: Double, rightX: Double,
        d: Long,
        log: (String) -> Unit,
    ): Pair<Boolean, String> {
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
     * 走位手势自检：把"往左推一次"的 4 种发法各跑一遍，让用户看**哪一种角色真的走**。
     *
     * ## 为什么需要它
     * 「角色不走」可能是坐标/让路的问题，也可能是**手势本身**这台机器上的游戏不认：
     * `input motionevent` 的 DOWN / MOVE / UP 是**各自起一个进程**发的，时间戳与 downTime
     * 由各进程生成，有些 ROM/游戏会把它当成残缺输入丢掉 —— 日志里一切正常，角色纹丝不动。
     * 这只有实机能回答，所以把四种发法摆出来，用户在画面上看哪一号动。
     *
     * 结果只写日志（不判断"动没动" —— 那只有人眼能判断）。
     */
    fun selfTest(log: (String) -> Unit) {
        val c = ctx ?: return
        val joy = Picks.joystick(c)
        val cx = joy.first.toDouble()
        val cy = joy.second.toDouble()
        val leftX = (cx - pushPct / 100.0).coerceIn(0.02, 0.98)
        val hold = 700
        val r = walkRectPx(c, cx, cy, leftX, leftX)

        log("── 走位手势自检：轮盘中心 [%.4f, %.4f]，推杆 ${pushPct}% ──".format(cx, cy))
        log("   轮盘中心${if (Picks.joystickAnnotated(c)) "已标注" else "未标注（用默认值）"}" +
            "，每项都往左推 ${hold}ms，中间停 1.3 秒 —— 请看角色哪一种动了")

        InjectShield.aroundWalk(r[0], r[1], r[2], r[3]) {
            listOf(
                "me" to "① motionevent：中心按下 → 拖到左侧 → 持续补 MOVE → 松开（当前默认通道）",
                "swipe" to "② input swipe：中心 → 左侧，时长 ${hold}ms（单进程，边拖边推）",
                "holdAt" to "③ input swipe：直接在左侧按住 ${hold}ms（单进程，按下点就在推杆位）",
                "meAt" to "④ motionevent：按下点就在左侧（不经过中心）"
            ).forEachIndexed { i, (kind, desc) ->
                log("   $desc")
                val out = runCatching {
                    ShellCore.probe.gestureTest(kind, cx, cy, leftX, cy, hold)
                }.getOrElse { "异常 ${it.javaClass.simpleName}: ${it.message}" }
                log("      $out")
                if (i < 3) sleep(1300)
            }
        }
        log("   自检结束（角色会往左偏一点，正常）。哪一号动了就把编号告诉我。")
    }

    /**
     * 整段走位在屏幕上覆盖的像素矩形 `[左, 上, 右, 下]`，给悬浮窗让路用。
     *
     * 用 displayMetrics 换算（与 [Probe] 里 screencap 出来的尺寸应当一致），并**外扩**
     * [WALK_RECT_PAD] 像素：重叠判定宁可判宽一点（面板多让一次），也不能漏判
     * （漏判就意味着注入又被面板吃掉，又变成"角色不动"）。
     */
    private fun walkRectPx(c: Context, cx: Double, cy: Double, leftX: Double, rightX: Double): IntArray {
        val m = c.resources.displayMetrics
        val w = m.widthPixels
        val h = m.heightPixels
        var l = (minOf(cx, leftX, rightX) * w).toInt()
        var r = (maxOf(cx, leftX, rightX) * w).toInt()
        var t = (cy * h).toInt()
        var b = (cy * h).toInt()
        // 跳跃点也要算进来：它同样是注入，同样会被挡
        Picks.get(c, Picks.JUMP)?.let { j ->
            val jx = (j.first * w).toInt()
            val jy = (j.second * h).toInt()
            l = minOf(l, jx); r = maxOf(r, jx)
            t = minOf(t, jy); b = maxOf(b, jy)
        }
        val pad = WALK_RECT_PAD
        return intArrayOf(l - pad, t - pad, r + pad, b + pad)
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
