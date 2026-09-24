package com.altair.probe

import android.content.Context
import java.util.Locale

/**
 * 标注点仓库（全 App 唯一的坐标来源）
 * ==================================
 *
 * 前提：**不做任何自动识别**。技能图标、跳跃键、轮盘中心，全部由用户在游戏画面上
 * 手动点一下标注，标注结果落盘，下次开软件直接沿用（用户要求）。
 *
 * ## 为什么彻底放弃自动识别
 * 自动识别出来的坐标一旦偏了，代价不是"点不准"而是**点到游戏里别的地方**
 * （确认框、购买、传送），后果不可控，而且从日志上看不出来为什么。
 * 手动标注一次只要几秒，且永远不会"偏半个按钮"。历史教训见
 * `docs/详细设计.md` 关于技能带 74px 相位吸附的那一节。
 *
 * ## 槽位
 * ```
 *   技能1..技能4   → 补 BUFF 时依次点的位置（对应设置的 BUFF1..BUFF4）
 *   跳跃           → 原地走位结尾点一下的位置
 *   轮盘           → 左下角摇杆中心；推杆以它为原点，走位时左推/右推
 * ```
 *
 * ## 存储
 * SharedPreferences `picks`，每个槽位一个键，值形如 `"0.74160,0.56730"`：
 * **归一化坐标**（0~1，按屏幕宽高比例），横竖屏切换也不会点偏。
 * 用文本而不是编码整数，是为了 `adb shell` 里直接看得懂、能手工改。
 *
 * ## 旧数据自动迁移
 * v0.24 及以前坐标散在两处：`overlay/pickedPoints`（JSON 数组，前 4 个是技能 1-4）
 * 与 `market/joyCxMilli,joyCyMilli`（轮盘中心）。首次读取时自动搬过来，老用户不必重标。
 */
object Picks {

    // ------------------------------------------------------------ 槽位定义

    const val SKILL_COUNT = 4

    const val SKILL1 = "skill1"
    const val SKILL2 = "skill2"
    const val SKILL3 = "skill3"
    const val SKILL4 = "skill4"
    const val JUMP = "jump"
    const val JOYSTICK = "joystick"

    /** 技能槽位，顺序 = 显示顺序（技能1..技能4）。 */
    val SKILLS = listOf(SKILL1, SKILL2, SKILL3, SKILL4)

    /** 全部槽位。 */
    val ALL = SKILLS + listOf(JUMP, JOYSTICK)

    fun label(slot: String): String = when (slot) {
        SKILL1 -> "技能1"
        SKILL2 -> "技能2"
        SKILL3 -> "技能3"
        SKILL4 -> "技能4"
        JUMP -> "跳跃"
        JOYSTICK -> "轮盘中心"
        else -> slot
    }

    /**
     * 全 App 统一的触摸按压参数。
     *
     * 历史上有过一个「按法」按钮在 短50/中90/长250/自绘150 之间循环 —— 那是为了排查
     * "游戏接受哪种触摸"而存在的调试入口。既然触摸通道已经定下来（90ms + swipe 实测可用），
     * 多档切换就是纯粹的复杂度：它还是"悬浮窗点1生效、引擎点击无效"这类 bug 的温床
     * （两边档位不一致）。现在**定死一档**，两边共用同一份常量。
     */
    const val TAP_PRESS_MS = 90
    const val TAP_METHOD = "swipe"

    // ------------------------------------------------------------ 存储

    private const val PREF = "picks"
    private const val MIGRATED = "migratedLegacy"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 格式化归一化坐标。显式 Locale.US —— 某些区域用逗号做小数点，会毁掉 `"x,y"` 格式。 */
    private fun fmt(v: Float): String = String.format(Locale.US, "%.5f", v)

    /** 读一个槽位；没标注（或数据坏了）返回 null。 */
    fun get(ctx: Context, slot: String): Pair<Float, Float>? {
        migrateLegacy(ctx)
        val raw = sp(ctx).getString(slot, null) ?: return null
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val x = parts[0].trim().toFloatOrNull() ?: return null
        val y = parts[1].trim().toFloatOrNull() ?: return null
        if (x !in 0f..1f || y !in 0f..1f) return null
        return x to y
    }

    /** 写一个槽位。这是"改一个点不动其它点"的单点写入口。 */
    fun set(ctx: Context, slot: String, x: Float, y: Float) {
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        sp(ctx).edit().putString(slot, "${fmt(cx)},${fmt(cy)}").apply()
    }

    /** 清掉单个槽位。 */
    fun clear(ctx: Context, slot: String) {
        sp(ctx).edit().remove(slot).apply()
    }

    /** 清空所有标注（保留迁移标记，免得把旧数据又搬回来一遍）。 */
    fun clearAll(ctx: Context) {
        sp(ctx).edit().clear().putBoolean(MIGRATED, true).apply()
    }

    // ------------------------------------------------------------ 查询辅助

    /** 4 个技能坐标，缺的为 null。 */
    fun skills(ctx: Context): List<Pair<Float, Float>?> = SKILLS.map { get(ctx, it) }

    /** 技能 1-4 是否都标注了。补 BUFF 的启动前提。 */
    fun skillsReady(ctx: Context): Boolean = SKILLS.all { get(ctx, it) != null }

    /** 还没标注的槽位中文名。 */
    fun missing(ctx: Context): List<String> = ALL.filter { get(ctx, it) == null }.map { label(it) }

    /** 缺哪些技能（中文名），给启动失败提示用。 */
    fun missingSkills(ctx: Context): List<String> =
        SKILLS.filter { get(ctx, it) == null }.map { label(it) }

    /**
     * 轮盘中心。没标注时给一个左下角常见位置的兜底值 ——
     * "没标也能试走一次"，比直接拒绝更有用；界面上会明确提示"用默认位置"。
     */
    fun joystick(ctx: Context): Pair<Float, Float> = get(ctx, JOYSTICK) ?: (0.12f to 0.80f)

    fun joystickAnnotated(ctx: Context): Boolean = get(ctx, JOYSTICK) != null

    // ------------------------------------------------------------ 按标注点触摸

    /**
     * 点一个标注过的槽位。
     *
     * 返回 (是否成功, 说明)。成功判定沿用历史约定：tapNorm 的输出里含"点击"。
     * 引擎与悬浮窗都走这一个入口，保证两边按压参数**永远一致**。
     */
    fun tap(ctx: Context, slot: String, what: String = ""): Pair<Boolean, String> {
        val p = get(ctx, slot) ?: return false to "「${label(slot)}」还没有标注"
        return tapPoint(p, what.ifBlank { label(slot) })
    }

    /** 点一个裸坐标。 */
    fun tapPoint(p: Pair<Float, Float>, what: String): Pair<Boolean, String> {
        val r = runCatching {
            ShellCore.probe.tapNorm(
                p.first.toDouble(), p.second.toDouble(), what, TAP_PRESS_MS, TAP_METHOD
            )
        }.getOrElse { "触摸调用异常：${it.javaClass.simpleName}: ${it.message}" }
        return r.contains("点击") to r.trim()
    }

    // ------------------------------------------------------------ 展示

    /** 多行清单，供「设置」页与日志展示。 */
    fun describe(ctx: Context): String = ALL.joinToString("\n") { slot ->
        val p = get(ctx, slot)
        if (p == null) {
            String.format(Locale.US, "  %-8s = （未标注）", label(slot))
        } else {
            String.format(Locale.US, "  %-8s = [%.4f, %.4f]", label(slot), p.first, p.second)
        }
    }

    // ------------------------------------------------------------ 旧数据迁移

    /**
     * 一次性迁移 v0.24 的坐标。迁移标记写在 `picks` 里，成功与否都只跑一次。
     *
     * 注意 `clearAll()` 会保留该标记 —— 否则用户"清空标注"之后，下次读又会被旧数据填回来。
     */
    private fun migrateLegacy(ctx: Context) {
        val p = sp(ctx)
        if (p.getBoolean(MIGRATED, false)) return
        val e = p.edit().putBoolean(MIGRATED, true)

        // 技能 1-4：旧格式 = overlay/pickedPoints 的 JSON 数组 [[x,y],[x,y]...]
        runCatching {
            val raw = ctx.getSharedPreferences("overlay", Context.MODE_PRIVATE)
                .getString("pickedPoints", "") ?: ""
            if (raw.isNotBlank()) {
                val arr = org.json.JSONArray(raw)
                for (i in 0 until minOf(SKILL_COUNT, arr.length())) {
                    val o = arr.optJSONArray(i) ?: continue
                    val x = o.optDouble(0, -1.0).toFloat()
                    val y = o.optDouble(1, -1.0).toFloat()
                    if (x in 0f..1f && y in 0f..1f) {
                        e.putString(SKILLS[i], "${fmt(x)},${fmt(y)}")
                    }
                }
            }
        }

        // 轮盘中心：旧格式 = market/joyCxMilli, joyCyMilli（千分比整数）
        runCatching {
            val m = ctx.getSharedPreferences("market", Context.MODE_PRIVATE)
            val cx = m.getLong("joyCxMilli", -1L)
            val cy = m.getLong("joyCyMilli", -1L)
            if (cx >= 0 && cy >= 0) {
                e.putString(JOYSTICK, "${fmt((cx / 1000.0).toFloat())},${fmt((cy / 1000.0).toFloat())}")
            }
        }

        e.apply()
    }
}
