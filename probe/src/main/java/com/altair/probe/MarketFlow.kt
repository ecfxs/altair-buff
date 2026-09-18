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

    /**
     * 走位方式：`joystick`（默认）| `key`。
     *
     * 默认摇杆 —— 用户实测方向键走位不好用，左下角摇杆才是游戏自己的移动入口。
     * 保留 key 作为兜底（某些环境下触摸通道不可用）。
     */
    var walkMethod: String
        get() = getStr("walkMethod", "joystick")
        set(v) = setStr("walkMethod", v)

    /**
     * 摇杆中心（归一化）。默认取左下角常见位置；
     * 用悬浮窗「校摇杆」在游戏里点一下摇杆正中心即可精确标定。
     */
    var joystickCenterX: Double
        get() = getLong("joyCxMilli", 120L) / 1000.0
        set(v) = setLong("joyCxMilli", (v * 1000).toLong())

    var joystickCenterY: Double
        get() = getLong("joyCyMilli", 800L) / 1000.0
        set(v) = setLong("joyCyMilli", (v * 1000).toLong())

    /** 推杆幅度（归一化，相对摇杆中心）。越大走得越快。 */
    var joystickRadius: Double
        get() = getLong("joyRadiusMilli", 60L) / 1000.0
        set(v) = setLong("joyRadiusMilli", (v * 1000).toLong())

    /** 每一步按住摇杆的时长（毫秒）= 走动时长。 */
    var joystickHoldMs: Long
        get() = getLong("joyHoldMs", 450L)
        set(v) = setLong("joyHoldMs", v)

    /**
     * 方向键：19=上 20=下 21=左 22=右。
     * 默认**左**（21）—— 用户确认自由市场的出口在左侧（"走到左侧出口出"）。
     */
    var walkKeyCode: Int
        get() = getLong("walkKeyCode", 21L).toInt()
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

    /**
     * 原地走动的横向距离（**像素**，默认 100px —— 用户指定"左右走大概 100 像素"）。
     *
     * 用像素而不是屏宽比例：换分辨率时"走多少路"的体感不变，
     * 而比例写法在 720p/1080p 上实际位移会差一倍。
     * 运行时按 [ShellCore.probe.screenWidthPx] 换算成归一化坐标。
     */
    var strollDistancePx: Int
        get() = getLong("strollPx", 100L).toInt()
        set(v) = setLong("strollPx", v.toLong())

    /** 来回几趟。默认 1 趟（左去右回）。 */
    var strollRounds: Int
        get() = getLong("strollRounds", 1L).toInt()
        set(v) = setLong("strollRounds", v.toLong())

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
     * 进自由市场 → 走到左侧出口 →（可选）出市场。返回 (是否成功, 说明)。
     *
     * @param leaveAfter 到了出口之后按【方向键上】出市场。
     *   · 悬浮窗「自由市场▶」按钮传 true —— 用户描述的完整组合键就是"进→走到左侧出口**出**"，
     *     这样它也是一个能自检的闭环（跑完应回到野外）
     *   · 引擎的回城模式传 false —— 进市场是为了**等待**，要停在出口待命，
     *     等下一轮到点再出市场补 BUFF
     * @param log 逐步日志回调（悬浮窗与引擎都传 LogBus::emit）
     */
    fun enterMarketAndWalkToExit(
        log: (String) -> Unit,
        leaveAfter: Boolean = false,
    ): Pair<Boolean, String> {
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
        if (!walked.first || !leaveAfter) return walked

        // ---- 6. 出市场（按方向键上）----
        log("⑥ 到了出口，按【方向键上】出市场")
        val (exited, exMsg) = exitMarket(log)
        return if (exited) {
            true to (walked.second + "；" + exMsg)
        } else {
            false to (walked.second + "；出市场失败：" + exMsg)
        }
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
        // 未标定出口 x：按用户给的机制走 —— **出口在左侧**，一直往左走到底（贴到地图左边界）
        // 就是出口。判定"到底"：连续 3 次血条 x 不再变小（贴边后角色动不了）。
        if (walkMode != "tap") {
            log("⑤ 出口未标定：按「出口在左侧」往左走到底（血条 x 不再变小即到达）")
            return walkLeftUntilEdge(log)
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
     * 往左走到底 = 到达自由市场的左侧出口。
     *
     * 判定依据：贴到地图左边界后角色走不动，血条 x 不再变小。
     * 连续 3 次没变小就认为到了 —— 比"走固定步数"可靠，因为云手机帧率与卡顿都会影响实际位移。
     */
    private fun walkLeftUntilEdge(log: (String) -> Unit): Pair<Boolean, String> {
        var prev = Double.MAX_VALUE
        var stalled = 0
        var steps = 0
        repeat(maxWalkSteps) {
            steps++
            val x = runCatching { ShellCore.probe.findHeadHpBarX() }.getOrNull()
            if (x != null) {
                if (x <= prev + 0.002) stalled++ else stalled = 0
                prev = x
                log("   第 $steps 步：血条 x=${"%.3f".format(x)}（连续未变小 $stalled/3）")
                if (stalled >= 3) {
                    return true to "已贴到左侧边界（血条 x=${"%.3f".format(x)}），应为出口"
                }
            }
            walkStep(-1, log)
        }
        return false to "往左走了 $steps 步仍未贴到边界（最后 x=${"%.3f".format(prev)}）"
    }

    /**
     * 原地等待模式：放技能前**左右走动一下再回原位**（用户确认的循环）。
     *
     * 用血条 x 做闭环回到原点，而不是"左走 N 步再右走 N 步" ——
     * 后者在卡顿时会越走越偏，几次循环就跑出原地了。
     */
    fun strollAndReturn(log: (String) -> Unit): Pair<Boolean, String> {
        // ① 标定当前位置：读一次角色血条 x，作为"走回这里"的原点
        val origin = hpX() ?: return false to "没识别到角色血条，跳过原地走动（直接补 BUFF）"
        val screenW = ShellCore.probe.screenWidthPx.coerceAtLeast(1)
        val dist = strollDistancePx.toDouble() / screenW      // 100px → 归一化
        val tol = 0.012
        log("① 已标定当前位置：血条 x=${"%.3f".format(origin)}（屏幕 ${screenW}px，本次走 ${strollDistancePx}px ≈ ${"%.3f".format(dist)}）")

        var x = origin
        var steps = 0
        repeat(strollRounds) { round ->
            // ② 左腿：往左走 strollDistancePx
            var i = 0
            while (i < maxWalkSteps) {
                x = hpX() ?: break
                if (origin - x >= dist) break
                walkStep(-1, log)
                i++; steps++
            }
            log("② 左腿：x=${"%.3f".format(x)}（目标 ≤ ${"%.3f".format(origin - dist)}，走了 $i 步）")

            // ③ 右腿：走回原点（闭环，不靠步数）
            var j = 0
            while (j < maxWalkSteps) {
                x = hpX() ?: break
                if (x >= origin - tol) break
                walkStep(+1, log)
                j++; steps++
            }
            log("③ 右腿：x=${"%.3f".format(x)}（目标 ≥ ${"%.3f".format(origin - tol)}，走了 $j 步）")
        }

        val back = kotlin.math.abs(x - origin) <= 0.02
        return true to ("④ 原地走动完成：原点 x=${"%.3f".format(origin)}，回位 x=${"%.3f".format(x)}，" +
            "共 $steps 步" + if (back) "（已回原位，接着补 BUFF）" else "（⚠ 未完全回位，仍继续补 BUFF）")
    }

    /**
     * 走一步：dir = -1 左 / +1 右。
     *
     * 默认**方向键**（用户实测"方向键版本能略微走动"），需要时切 joystick 走左下角摇杆。
     */
    private fun walkStep(dir: Int, log: (String) -> Unit = {}) {
        if (walkMethod == "key") {
            runCatching { ShellCore.probe.sendKey(if (dir < 0) KEY_LEFT else KEY_RIGHT) }
            sleep(walkGapMs)
            return
        }
        val cx = joystickCenterX
        val cy = joystickCenterY
        val mx = (cx + dir * joystickRadius).coerceIn(0.02, 0.98)
        val r = runCatching {
            ShellCore.probe.joystickWalk(cx, cy, mx, cy, joystickHoldMs.toInt())
        }.getOrDefault("摇杆调用失败")
        log("   摇杆走${if (dir < 0) "左" else "右"}：$r")
        sleep(walkGapMs)
    }

    /** 读一次血条中心 x（失败返回 null）。 */
    private fun hpX(): Double? = runCatching { ShellCore.probe.findHeadHpBarX() }.getOrNull()

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
            log("   第 ${i + 1} 步：血条 x=${"%.3f".format(x)} → 目标 ${"%.3f".format(target)}，往${if (dx > 0) "右" else "左"}")
            walkStep(if (dx > 0) 1 else -1, log)
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
    fun describeStroll(): String =
        "原地走动：标定当前位置 → 走 ${strollDistancePx}px × ${strollRounds} 趟 → 回原位；走法=" +
            if (walkMethod == "key") {
                "方向键"
            } else {
                "摇杆 中心(${"%.3f".format(joystickCenterX)},${"%.3f".format(joystickCenterY)}) " +
                    "推杆 ${"%.3f".format(joystickRadius)} 保持 ${joystickHoldMs}ms"
            }

    /** 供界面显示当前参数。 */
    fun describe(): String =
        "进市场：菜单等待 ${menuWaitMs}ms / 过图等待 ${loadingWaitMs}ms；" +
            "走位=" + (if (walkMethod == "key") "方向键" else "摇杆") + " " + if (exitXNorm >= 0) {
                "闭环对齐血条 → 出口 x=${"%.3f".format(exitXNorm)}"
            } else {
                if (walkMode == "tap") "点传送点（未标定出口）" else "开环方向键 $walkKeyCode ×$walkRepeats（未标定出口）"
            }
}
