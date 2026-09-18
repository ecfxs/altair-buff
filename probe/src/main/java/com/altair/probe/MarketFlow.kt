package com.altair.probe

import android.content.Context

/**
 * 进出自由市场的完整流程
 * ======================
 *
 * 一条流程串起四件事，每一步都有**可观测的判定**，不是一路 sleep 到底：
 *
 * ```
 *   补完 BUFF（在野外）
 *      │
 *      ├─ 1. 点「菜单」          ← 采点第 5 个（index 4）
 *      ├─ 2. 等菜单真的出现       ← 整屏平均亮度骤降（半透明遮罩）
 *      ├─ 3. 点「自由市场」       ← 采点第 6 个（index 5）
 *      ├─ 4. 等过图黑屏「出现又过去」← 方差/熵判定，先等它黑、再等它不黑
 *      └─ 5. 走到出口             ← 采点第 7 个（传送点）：走过去 / 或点它
 *
 *   下轮到点：按【方向键上】出市场 → 回野外 → 补 BUFF
 * ```
 *
 * ## 诚实的边界
 * 第 5 步「走到出口」目前是**开环**的：方向键连发固定时长，或者直接点传送点坐标。
 * 设计文档 6.6 里写的是闭环（检测头顶血条定位角色 + 检测传送门特征，逐步逼近），
 * 但那需要设备端的模板匹配/NCC —— **APK 里目前没有任何设备端视觉**，只有
 * 「整屏方差/熵/亮度」这种统计量。所以闭环留到有设备端视觉之后再做，
 * 现在把方向和时长做成可调，先能跑通、能观察。
 *
 * ## 为什么每一步都要判定
 * 云手机帧率波动大，固定 sleep 在不同机器上表现完全不同：
 * 快的机器白等，慢的机器还没进市场就点了下一步。宁可多花一次截图，
 * 也不要"看起来执行完了其实什么都没发生"。
 */
object MarketFlow {

    private const val PREF = "market"

    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    // ------------------------------------------------------------ 可调参数
    //
    // 全部落 SharedPreferences：不同云手机的性能差异很大（帧率、过图时长），
    // 写死常数一定会有一批机器跑不对。默认值取「保守 + 可观察」，能跑通再收紧。

    private fun prefs() = ctx?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun getLong(k: String, def: Long): Long = prefs()?.getLong(k, def) ?: def

    private fun setLong(k: String, v: Long) {
        prefs()?.edit()?.putLong(k, v)?.apply()
    }

    private fun getStr(k: String, def: String): String = prefs()?.getString(k, def) ?: def

    private fun setStr(k: String, v: String) {
        prefs()?.edit()?.putString(k, v)?.apply()
    }

    /** 点完菜单后，等它弹出来的上限。 */
    var menuWaitMs: Long
        get() = getLong("menuWaitMs", 2500L)
        set(v) = setLong("menuWaitMs", v)

    /** 点完自由市场后，等过图黑屏的时长上限。 */
    var loadingWaitMs: Long
        get() = getLong("loadingWaitMs", 12000L)
        set(v) = setLong("loadingWaitMs", v)

    /** 点完/走完之后的收尾等待（让游戏缓一下再执行下一步）。 */
    var settleMs: Long
        get() = getLong("settleMs", 600L)
        set(v) = setLong("settleMs", v)

    /** 第 5 步：走到出口的方式 —— keyRepeat（方向键连发）或 tap（点传送点坐标）。 */
    var walkMode: String
        get() = getStr("walkMode", "keyRepeat")
        set(v) = setStr("walkMode", v)

    /** 方向键：19=上 20=下 21=左 22=右。出口在自由市场里通常朝一个固定方向。 */
    var walkKeyCode: Int
        get() = getLong("walkKeyCode", 22L).toInt()
        set(v) = setLong("walkKeyCode", v.toLong())

    /** 连发次数（每次之间 [walkGapMs] 毫秒），总时长 ≈ 次数 × 间隔。 */
    var walkRepeats: Int
        get() = getLong("walkRepeats", 12L).toInt()
        set(v) = setLong("walkRepeats", v.toLong())

    var walkGapMs: Long
        get() = getLong("walkGapMs", 220L)
        set(v) = setLong("walkGapMs", v)

    /**
     * 出口（光圈门）的屏幕 x，归一化。用户走到门口按「记出口」标定一次。
     * < 0 表示还没标定 → 走位退化成开环。
     */
    var exitXNorm: Double
        get() {
            val v = getLong("exitXMilli", -1L)
            return if (v < 0) -1.0 else v / 1000.0
        }
        set(v) = setLong("exitXMilli", (v * 1000).toLong())

    /** 对齐容差（归一化）：|角色x - 出口x| 小于它就算到门口了。 */
    var alignTolerance: Double
        get() = getLong("alignTolMilli", 20L) / 1000.0
        set(v) = setLong("alignTolMilli", (v * 1000).toLong())

    /** 闭环最多走几步（每步一次截图，别无限走）。 */
    var maxWalkSteps: Int
        get() = getLong("maxWalkSteps", 24L).toInt()
        set(v) = setLong("maxWalkSteps", v.toLong())

    /** 采点下标：4=菜单 5=自由市场 6=传送点（与 OverlayService 的 pickLabels 一致）。 */
    private const val IDX_MENU = 4
    private const val IDX_MARKET = 5
    private const val IDX_PORTAL = 6

    /** 方向键：19=上 20=下 21=左 22=右（详细设计 F-14 出市场用上）。 */
    private const val KEY_UP = 19
    private const val KEY_LEFT = 21
    private const val KEY_RIGHT = 22

    // ------------------------------------------------------------ 主流程

    /**
     * 进自由市场并走到出口待命。返回 (是否成功, 说明)。
     *
     * @param log 逐步日志回调（悬浮窗与引擎都传 LogBus::emit）
     */
    fun enterMarketAndWalkToExit(log: (String) -> Unit): Pair<Boolean, String> {
        val c = ctx ?: return false to "未初始化"
        val picks = OverlayService.pickedPointsOf(c)
        if (picks.size <= IDX_MARKET) {
            return false to "缺少采点：需要至少 6 个点（菜单 + 自由市场），当前 ${picks.size} 个"
        }

        // ---- 1. 点菜单 ----
        // ★ 亮度基线必须在**点击之前**量。点完再量的话，菜单可能已经开着，
        //   基线本身就是暗的，"整屏变暗"这个判定就永远不会触发（这个顺序我第一版写错过）。
        val before = runCatching { ShellCore.probe.screenBrightness() }.getOrNull()
        log("① 点「菜单」")
        tap(picks[IDX_MENU], "菜单")
        sleep(settleMs)

        // ---- 2. 等菜单出现（整屏变暗） ----
        val menuShown = waitUntil(menuWaitMs, log, "② 等菜单出现") {
            val now = runCatching { ShellCore.probe.screenBrightness() }.getOrNull()
            if (now == null) return@waitUntil null      // 取不到画面 → 不判定，等超时
            if (before == null) return@waitUntil null
            // 半透明遮罩会让整屏平均亮度下降；降 8 以上认为菜单出来了
            (before - now) >= 8.0
        }
        if (!menuShown) log("   ⚠ 没检测到菜单变暗（可能是亮度判定不适用），仍继续下一步")

        // ---- 3. 点自由市场 ----
        log("③ 点「自由市场」")
        tap(picks[IDX_MARKET], "自由市场")

        // ---- 4. 等过图黑屏「出现又过去」 ----
        val sawBlack = waitUntil(loadingWaitMs, log, "④ 等过图黑屏出现") {
            runCatching { ShellCore.probe.isScreenBlack() }.getOrNull() == true
        }
        if (sawBlack) {
            val passed = waitUntil(loadingWaitMs, log, "   等黑屏过去") {
                runCatching { ShellCore.probe.isScreenBlack() }.getOrNull() == false
            }
            if (!passed) return false to "过图黑屏一直没结束（超时 ${loadingWaitMs}ms）"
        } else {
            log("   ⚠ 没检测到黑屏（有些机器过图不黑屏），按已进市场继续")
        }
        sleep(settleMs)

        // ---- 5. 走到出口 ----
        val walked = walkToPortal(picks, log)
        return walked
    }

    /**
     * 出自由市场：按【方向键上】（详细设计 F-14）。
     * 出完之后同样等一次过图黑屏。
     */
    fun exitMarket(log: (String) -> Unit): Pair<Boolean, String> {
        log("按【方向键上】出自由市场")
        val r = runCatching { ShellCore.probe.sendKey(KEY_UP) }.getOrDefault("")
        if (!r.contains("已发送")) return false to "方向键上发送失败：${r.take(80)}"
        val passed = waitUntil(loadingWaitMs, log, "等出市场过图") {
            runCatching { ShellCore.probe.isScreenBlack() }.getOrNull() == false
        }
        sleep(settleMs)
        return if (passed) true to "已出自由市场" else false to "出市场后画面判定超时"
    }

    // ------------------------------------------------------------ 走位

    private fun walkToPortal(
        picks: List<Pair<Float, Float>>,
        log: (String) -> Unit,
    ): Pair<Boolean, String> {
        // ---- 优先：闭环对齐（血条 x → 出口 x）----
        val target = exitXNorm
        if (target >= 0) {
            log("⑤ 闭环走位：把血条对齐到出口 x=${"%.3f".format(target)}（容差 ${"%.3f".format(alignTolerance)}）")
            return walkAligned(target, log)
        }
        log("⑤ 出口 x 未标定（走到门口按一次「记出口」），退化为开环走位")
        if (walkMode == "tap") {
            if (picks.size <= IDX_PORTAL) {
                return false to "走位方式=tap，但没有第 7 个采点（传送点）"
            }
            log("⑤ 点「传送点」（走位方式=tap）")
            tap(picks[IDX_PORTAL], "传送点")
            return true to "已点传送点"
        }

        log("⑤ 方向键连发走位：键=$walkKeyCode × $walkRepeats 次（间隔 ${walkGapMs}ms）")
        var sent = 0
        repeat(walkRepeats) {
            val r = runCatching { ShellCore.probe.sendKey(walkKeyCode) }.getOrDefault("")
            if (r.contains("已发送")) sent++
            sleep(walkGapMs)
        }
        if (sent == 0) return false to "方向键一次都没发出去"
        return true to "已朝出口走 $sent 次（开环，未做视觉校准）"
    }

    /**
     * 闭环走位：每步重新找血条 x，朝目标步进。
     *
     * 为什么不一次走到底：云手机帧率波动 + 游戏卡顿会让实际位移和预期差很多，
     * 每步重新检测就把误差限制在单步内（这是设计文档 6.6 的原意）。
     */
    private fun walkAligned(target: Double, log: (String) -> Unit): Pair<Boolean, String> {
        var lastX = -1.0
        var stuck = 0
        var noFrame = 0
        repeat(maxWalkSteps) { i ->
            val x = runCatching { ShellCore.probe.findHeadHpBarX() }.getOrNull()
            if (x == null) {
                noFrame++
                if (noFrame >= 5) {
                    return false to "连续 $noFrame 次找不到角色血条 —— 血条被挡/不在 ${"%.0f".format(50.0)}~60% 高度带里？"
                }
                sleep(walkGapMs)
                return@repeat
            }
            noFrame = 0
            val dx = target - x
            if (kotlin.math.abs(dx) <= alignTolerance) {
                return true to "已对齐出口：血条 x=${"%.3f".format(x)} ≈ 目标 ${"%.3f".format(target)}（${i + 1} 步）"
            }
            if (lastX >= 0 && kotlin.math.abs(x - lastX) < 0.004) stuck++ else stuck = 0
            if (stuck >= 4) {
                return false to "走位卡住：血条 x 连续 ${stuck} 步停在 ${"%.3f".format(x)}"
            }
            lastX = x
            val key = if (dx > 0) KEY_RIGHT else KEY_LEFT
            log("   第 ${i + 1} 步：血条 x=${"%.3f".format(x)} → 目标 ${"%.3f".format(target)}，按 ${if (dx > 0) "右" else "左"}")
            runCatching { ShellCore.probe.sendKey(key) }
            sleep(walkGapMs)
        }
        return false to "走满 $maxWalkSteps 步仍未对齐（最后 x=${"%.3f".format(lastX)}，目标 ${"%.3f".format(target)}）"
    }

    /** 把**当前**位置记成出口：走到光圈门口按一次即可。 */
    fun recordExitHere(log: (String) -> Unit): Pair<Boolean, String> {
        val x = runCatching { ShellCore.probe.findHeadHpBarX() }.getOrNull()
            ?: return false to "找不到角色血条，没法记录（先确认 ROI 能框到角色）"
        exitXNorm = x
        log("已记录出口 x=${"%.3f".format(x)}")
        return true to "出口 x 已记录：${"%.3f".format(x)}"
    }

    /** 探一次血条位置，用于现场确认检测是否有效。 */
    fun probeHpBar(): String {
        val x = runCatching { ShellCore.probe.findHeadHpBarX() }.getOrNull()
            ?: return "没找到角色血条（检查：角色是否在画面里、血条是否被 UI 挡住）"
        return "血条中心 x=${"%.3f".format(x)}（出口目标 x=${if (exitXNorm < 0) "未标定" else "%.3f".format(exitXNorm)}）"
    }

    // ------------------------------------------------------------ 工具

    private fun tap(p: Pair<Float, Float>, what: String) {
        runCatching {
            ShellCore.probe.tapNorm(p.first.toDouble(), p.second.toDouble(), what)
        }
    }

    /**
     * 轮询等待条件成立。
     *
     * @param timeoutMs 上限；到期返回 false（调用方决定是继续还是失败）
     * @param check 返回 true=成立 false=还没到 null=本次取不到画面（跳过这一次，不判失败）
     */
    private inline fun waitUntil(
        timeoutMs: Long,
        log: (String) -> Unit,
        label: String,
        check: () -> Boolean?,
    ): Boolean {
        val t0 = System.currentTimeMillis()
        var polls = 0
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            polls++
            if (check() == true) {
                log("   $label：第 $polls 次判定成立（${System.currentTimeMillis() - t0}ms）")
                return true
            }
            sleep(350)
        }
        return false
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    /** 供界面显示当前参数。 */
    fun describe(): String =
        "进市场：菜单等待 ${menuWaitMs}ms / 过图等待 ${loadingWaitMs}ms；" +
            "走位=" + if (exitXNorm >= 0) {
                "闭环对齐血条 → 出口 x=${"%.3f".format(exitXNorm)}"
            } else {
                if (walkMode == "tap") "点传送点（未标定出口）" else "开环方向键 $walkKeyCode ×$walkRepeats（未标定出口）"
            }
}
