package com.altair.probe

import android.content.Context

/** 三段等幅推杆，最后松手等待 1 秒再跳。纯计时只能近似回位，不检测角色位置。 */
object WalkFlow {

    private const val PREF = "walk"

    @Volatile private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    private fun prefs() = ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 参数

    /**
     * 原地走位总开关。默认**开**（沿用一直以来的行为，不给老用户突然少一项功能）。
     *
     * 关闭时引擎把走位周期配成 0，于是完全不排期、不碰摇杆 —— 只补 BUFF。
     * 轮盘中心也随之不再是必需标记（[Picks.required] 会跟着这个开关变）：
     * 不走位就不需要摇杆，没道理还拦着不让启动。
     *
     * 「试走位一次」不受它影响：那是用户主动点的手动验证，本来就该随时能用。
     */
    var enabled: Boolean
        get() = prefs()?.getBoolean("enabled", true) ?: true
        set(v) {
            prefs()?.edit()?.putBoolean("enabled", v)?.apply()
        }

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

    fun strollAndJump(log: (String) -> Unit): Pair<Boolean, String> = Actions.run("走位与跳跃") { token ->
        token.check()
        val c = checkNotNull(ctx) { "未初始化" }
        val geometry = Picks.requireGeometry(c, listOf(Picks.JOYSTICK))
        val joy = Picks.joystick(c)
        val jump = Picks.get(c, Picks.JUMP)
        val cx = geometry.x(joy.first)
        val cy = geometry.y(joy.second)
        val offset = (geometry.width * pushPct / 100).coerceAtLeast(1)
        // 不能分别裁剪左右端点，否则推杆幅度不同，1:2:1 的时长也无法抵消位移。
        check(cx - offset >= 0 && cx + offset < geometry.width) { "推杆幅度超出屏幕，请调小幅度或重新标记轮盘" }
        val d = legMs
        val press = jumpPressMs

        // 跳跃是**可选**项：没标记就走完三段收工，并在日志里说明，而不是把整次走位判失败。
        val args: List<String>
        val budgetMs: Long
        val inject: List<Int>
        if (jump == null) {
            log("左 ${d}ms → 右 ${d * 2}ms → 左 ${d}ms → 松手（跳跃未标记，本次不跳）")
            args = listOf("walk3", "$cx", "$cy", "${cx - offset}", "${cx + offset}", "$d")
            budgetMs = d * 3 + 500
            inject = listOf(cx - offset - 48, cy - 48, cx + offset + 48, cy + 48)
        } else {
            val jx = geometry.x(jump.first)
            val jy = geometry.y(jump.second)
            log("左 ${d}ms → 右 ${d * 2}ms → 左 ${d}ms → 松手等待 1 秒 → 跳一次（${press}ms）")
            args = listOf("walk", "$cx", "$cy", "${cx - offset}", "${cx + offset}",
                "$jx", "$jy", "$d", "$press")
            budgetMs = d * 4 + 1500 + press
            inject = listOf(minOf(cx - offset, jx) - 48, minOf(cy, jy) - 48,
                maxOf(cx + offset, jx) + 48, maxOf(cy, jy) + 48)
        }
        InjectShield.aroundWalk(inject[0], inject[1], inject[2], inject[3]) {
            ShellCore.probe.perform(args, budgetMs, token, geometry)
        }
    }
}
