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
 *   技能1..技能4   → 补 BUFF 时依次点的位置（**必需**，但只要求"已勾选启用"的那几个）
 *   轮盘           → 左下角摇杆中心；推杆以它为原点，走位时左推/右推（**必需**）
 *   跳跃           → 原地走位结尾点一下的位置（**可选**，缺了就走位不跳）
 * ```
 * 必需性只在 [required] 里定义一次，界面与引擎都从它派生 —— 见 [checklist]。
 *
 * ## 存储
 * SharedPreferences `picks`，每个槽位一个键，值形如 `"0.74160,0.56730"`：
 * **归一化坐标**（0~1），另存显示屏尺寸及方向；变化后必须重新标记。
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

    /** 技能按压时长；跳跃使用 WalkFlow 中单独保存的参数。 */
    const val TAP_PRESS_MS = 90

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
        sp(ctx).edit().putString(slot, "${fmt(cx)},${fmt(cy)}")
            .putString("${slot}_screen", ScreenGeometry.read(ctx).key).apply()
    }

    /** 清掉单个槽位。 */
    fun clear(ctx: Context, slot: String) {
        sp(ctx).edit().remove(slot).remove("${slot}_screen").apply()
    }

    /** 清空所有标注（保留迁移标记，免得把旧数据又搬回来一遍）。 */
    fun clearAll(ctx: Context) {
        sp(ctx).edit().clear().putBoolean(MIGRATED, true).apply()
    }

    // ------------------------------------------------------------ 查询辅助

    /** 当前勾选启用的技能槽位。 */
    fun enabledSkills(ctx: Context): List<String> {
        Engine.init(ctx)
        return Engine.buffConfig().filter { it.enabled }.map { SKILLS[it.idx] }
    }

    /**
     * 启动前**必须**标记的槽位：启用的技能 + 轮盘中心。
     *
     * ★ 跳跃**不在**其中。设计文档 2.2 明确：跳跃只影响走位结尾跳不跳，
     * 漏标它不该把整个任务拦在启动之外（那会让"能跑的"功能因为"锦上添花"的一项全废掉）。
     * 未标记时 [WalkFlow] 会走完三段并写日志说明这次没跳。
     */
    fun required(ctx: Context): List<String> = enabledSkills(ctx) + JOYSTICK

    /**
     * 界面清单：每项 = (槽位, 是否必需)。
     *
     * 界面要能一眼看出"哪些必须标、哪些标了更好"，所以必需性由 [required] 单点决定，
     * 而不是让每个界面各自判断一遍（那是"悬浮窗说缺 3 项、主界面说缺 2 项"的根源）。
     */
    fun checklist(ctx: Context): List<Pair<String, Boolean>> {
        val need = required(ctx).toSet()
        return ALL.map { it to (it in need) }
    }

    /** 跳跃是否已标记。未标记时走位照走，只是结尾不跳。 */
    fun jumpReady(ctx: Context): Boolean = get(ctx, JUMP) != null

    fun joystick(ctx: Context): Pair<Float, Float> =
        requireNotNull(get(ctx, JOYSTICK)) { "请先标记轮盘中心" }

    fun joystickAnnotated(ctx: Context): Boolean = get(ctx, JOYSTICK) != null

    fun requireGeometry(ctx: Context, slots: List<String>): ScreenGeometry {
        val geometry = ScreenGeometry.read(ctx)
        val invalid = slots.filter { get(ctx, it) == null || sp(ctx).getString("${it}_screen", null) != geometry.key }
        check(invalid.isEmpty()) { "请在当前游戏画面重新标记：${invalid.joinToString("、") { label(it) }}" }
        return geometry
    }

    fun tap(ctx: Context, slot: String, what: String = label(slot)): Pair<Boolean, String> =
        Actions.run(what) { token ->
            token.check()
            val geometry = requireGeometry(ctx, listOf(slot))
            val point = requireNotNull(get(ctx, slot)) { "${label(slot)}未标记" }
            val x = geometry.x(point.first)
            val y = geometry.y(point.second)
            InjectShield.aroundInject(x - 24, y - 24, x + 24, y + 24) {
                ShellCore.probe.perform(listOf("tap", "$x", "$y", "$TAP_PRESS_MS"), TAP_PRESS_MS.toLong(), token, geometry)
            }
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
                    if (x in 0f..1f && y in 0f..1f && !p.contains(SKILLS[i])) {
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
            if (cx in 0..1000 && cy in 0..1000 && !p.contains(JOYSTICK)) {
                e.putString(JOYSTICK, "${fmt((cx / 1000.0).toFloat())},${fmt((cy / 1000.0).toFloat())}")
            }
        }

        e.apply()
    }
}
