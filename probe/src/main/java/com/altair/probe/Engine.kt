package com.altair.probe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 挂机引擎
 * ========
 *
 * 这是主功能本体：按配置的周期，定时给角色补 BUFF。
 *
 * ## 状态机
 * ```
 *   IDLE ──start()──▶ WAITING ──到点──▶ CASTING ──成功──▶ WAITING（下一轮）
 *     ▲                  │                  │
 *     └────── stop() ────┴──── 失败重试后 ───┴──▶ ERROR（熔断，等人工）
 * ```
 *
 * ## 两条安全约束
 * 1. **前台门禁**：只有目标游戏在前台才执行。否则跳过本轮并记日志 ——
 *    无人值守时最危险的失败就是"在错误的界面上乱点"。
 * 2. **失败熔断**：连续失败 N 次就停下来等人工，绝不死循环重试。
 *
 * ## 输入方式可切换
 * 技能键到底走键盘还是触摸，实测一直没定论。所以做成配置项：
 *   keyevent —— `input keyevent 8/9/10/11`（需要游戏绑定数字键）
 *   touch    —— 在采集到的技能键坐标上 `input swipe`（需要先采点）
 * 引擎只调 `pressSkill(i)`，底下走哪条路由配置决定，换方式不用改引擎。
 */
object Engine {

    enum class State { IDLE, WAITING, CASTING, PAUSED, ERROR }

    @Volatile var state: State = State.IDLE
        private set
    @Volatile var nextDueAt: Long = 0L
        private set
    @Volatile var lastCastAt: Long = 0L
        private set
    @Volatile var cycleCount: Int = 0
        private set
    @Volatile var failStreak: Int = 0
        private set
    @Volatile var lastError: String = ""
        private set
    @Volatile var lastResult: String = ""
        private set

    private var ctx: Context? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    /** 连续失败多少次就熔断。 */
    private const val FAIL_LIMIT = 3

    // ------------------------------------------------------------ 配置读取

    private fun buffPrefs(): android.content.SharedPreferences? =
        ctx?.getSharedPreferences("buff", Context.MODE_PRIVATE)

    data class BuffCfg(val idx: Int, val enabled: Boolean, val key: Int, val durMin: Int)

    fun buffConfig(): List<BuffCfg> {
        // 上下文没注入时返回空配置，**绝不抛异常** —— 界面启动时就要读它算周期，
        // 这里一崩就是整个 App 崩（后台线程里的未捕获异常会带走进程）。
        val sp = buffPrefs() ?: return emptyList()
        return (0 until 3).map { i ->
            BuffCfg(
                idx = i,
                enabled = sp.getBoolean("enabled$i", i == 0),
                key = sp.getInt("key$i", i),
                durMin = sp.getInt("dur$i", 5)
            )
        }
    }

    /** 回城模式：补完 BUFF 自动进自由市场等待（配置项 autoFreeMarket，默认关）。 */
    private fun autoFreeMarket(): Boolean =
        buffPrefs()?.getBoolean("autoFreeMarket", false) ?: false

    /** 当前是否停在自由市场里。只在本进程内维护 —— 重启后按"未知"处理，下一轮会先尝试出市场。 */
    @Volatile private var inMarket = false
    /** "回城模式已暂停"只提示一次，别每轮刷屏。 */
    @Volatile private var pausedMarketNotified = false

    private fun inputMethod(): String =
        ctx?.getSharedPreferences("overlay", Context.MODE_PRIVATE)
            ?.getString("inputMethod", "keyevent") ?: "keyevent"

    /** 循环周期 = 最短 BUFF 时长 × 0.94（留 6% 余量吸收抖动与卡顿）。 */
    fun cyclePeriodMs(): Long {
        val d = buffConfig().filter { it.enabled }.minOfOrNull { it.durMin } ?: 0
        return if (d <= 0) 0L else (d * 60_000L * 0.94).toLong()
    }

    // ------------------------------------------------------------ 生命周期

    /**
     * 注入上下文。**界面 onCreate 时就要调**。
     *
     * 为什么必须提前调用：引擎没启动时也有代码要读配置 —— 主界面刷新状态的「周期」那一行
     * 会走 cyclePeriodMs → buffConfig。此前 ctx 只在 start() 里赋值，于是
     * 「更新后第一次打开」必崩：`pm install -r` 会重启进程，此时 ctx 还是 null，
     * 而后台线程里的 NPE 会直接带走整个 App —— 而且崩在「启动引擎」之前，
     * 所以之后每次打开都一样，用户看到的就是「一打开就闪退」。
     */
    fun init(context: Context) {
        ctx = context.applicationContext
        MarketFlow.init(context)
    }

    fun start(context: Context) {
        if (running) { LogBus.emit("引擎已在运行"); return }
        init(context)
        val period = cyclePeriodMs()
        if (period <= 0) {
            LogBus.emit("⛔ 无法启动：没有任何 BUFF 被启用，或时长未填。请到「设置」页配置。")
            state = State.ERROR; lastError = "没有启用任何 BUFF"
            return
        }
        val touch = inputMethod() == "touch"
        if (touch && OverlayService.pickedPointsOf(context).size < 4) {
            LogBus.emit("⛔ 无法启动：输入方式为触摸，但还没采集技能键坐标（需 4 个点）。")
            state = State.ERROR; lastError = "触摸方式但缺技能键坐标"
            return
        }
        // 回城模式先守「采点齐不齐」：宁可启动时明确拒绝，
        // 也不要每轮都在"进不去市场"里静默失败、还把日志刷满。
        if (autoFreeMarket()) {
            val picks = OverlayService.pickedPointsOf(context).size
            val need = if (MarketFlow.walkMode == "tap") 7 else 6
            if (picks < need) {
                LogBus.emit(
                    "⛔ 无法启动：开了回城模式，但采点不足（当前 $picks 个，需要 $need 个：" +
                        "技能1-4 → 菜单 → 自由市场" + (if (need == 7) " → 传送点" else "") +
                        "）。请到悬浮窗「★采点」依次补采。"
                )
                state = State.ERROR; lastError = "回城模式但采点不足（$picks/$need）"
                return
            }
        }
        running = true
        failStreak = 0
        lastError = ""
        nextDueAt = System.currentTimeMillis() + period
        state = State.WAITING
        LogBus.emit("▶ 引擎启动：周期 ${period / 60000.0} 分钟，输入方式=${inputMethod()}，" +
            "启用 ${buffConfig().count { it.enabled }} 个 BUFF")
        worker = Thread { loop() }.apply { isDaemon = true; name = "engine" }
        worker?.start()

        // 技能位：只保证有实测坐标（启动时已内置），**不做自动位移**。
        // 原因见 SkillBar 类注释：带内能稳定找到的相位是按钮"边缘"，应用了会把点推离中心。
        // 需要校正时用悬浮窗「技能位」按钮 —— 它重置为实测值并报告观测偏移。
        if (SkillBar.ensureDefaults(context)) {
            LogBus.emit("技能位：已内置实测坐标（悬浮窗「技能位」可重置/查看）")
        }
    }

    fun stop(reason: String = "手动停止") {
        if (!running) return
        running = false
        worker?.interrupt()
        worker = null
        state = State.IDLE
        nextDueAt = 0
        LogBus.emit("⏹ 引擎停止（$reason）")
    }

    val isRunning: Boolean get() = running

    // ------------------------------------------------------------ 主循环

    private fun loop() {
        while (running) {
            try {
                Thread.sleep(500)
                if (!running) break
                if (System.currentTimeMillis() >= nextDueAt) castRound()
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                LogBus.emit("引擎循环异常：$lastError")
                Thread.sleep(3000)
            }
        }
    }

    private fun castRound() {
        state = State.CASTING
        val c = ctx ?: return
        val target = OverlayService.targetPkgOf(c)

        // 门禁：只有目标游戏在前台才动手
        val fg = runCatching { ShellCore.probe.foregroundPackage() }.getOrDefault("")
        if (fg != target) {
            lastResult = "跳过：前台是「${fg.ifBlank { "未知" }}」，不是 $target"
            LogBus.emit("⏭ $lastResult")
            // 不累计失败 —— 这不是错误，只是时机不对；推迟 30 秒再看
            nextDueAt = System.currentTimeMillis() + 30_000
            state = State.WAITING
            return
        }

        // 【暂时停用】回城模式（进出自由市场）。
        // 用户要求：先只保留「技能位 + 角色血条」两项识别与「原地走动 + 补 BUFF」，
        // 菜单/自由市场/传送门/过图那套识别**代码保留但不再启用**，后续再优化。
        // 真要用时把下面这段的 false 改成 autoFreeMarket() 即可恢复。
        if (false && autoFreeMarket() && inMarket) {
            LogBus.emit("── 回城模式：先出自由市场 ──")
            val (ok, msg) = MarketFlow.exitMarket { LogBus.emit("  $it") }
            inMarket = false
            if (!ok) {
                failStreak++
                lastResult = "出自由市场失败：$msg"
                LogBus.emit("  ❌ $lastResult")
                nextDueAt = System.currentTimeMillis() + 30_000L * failStreak
                state = State.WAITING
                return
            }
        }

        // 补 BUFF 前：先标定当前位置 → 左右走一段 → 回原位（用户确认的循环）
        val (_, strollMsg) = MarketFlow.strollAndReturn { LogBus.emit("  $it") }
        LogBus.emit("  $strollMsg")
        if (autoFreeMarket() && !pausedMarketNotified) {
            pausedMarketNotified = true
            LogBus.emit("ℹ 回城模式（自动进自由市场）暂时停用：当前只做「原地走动 + 补 BUFF」，后续再启用")
        }

        val enabled = buffConfig().filter { it.enabled }
        LogBus.emit("── 第 ${cycleCount + 1} 轮：开始补 ${enabled.size} 个 BUFF ──")
        var ok = 0
        for (b in enabled) {
            val r = pressSkill(b.idx)
            if (r.first) { ok++; LogBus.emit("  ✅ BUFF${b.idx + 1}（键 ${b.key + 1}）${r.second}") }
            else LogBus.emit("  ❌ BUFF${b.idx + 1} 失败：${r.second}")
            try { Thread.sleep(900) } catch (_: InterruptedException) { break }
        }

        if (ok == enabled.size) {
            failStreak = 0
            cycleCount++
            lastCastAt = System.currentTimeMillis()
            lastResult = "完成：$ok/${enabled.size}"
            val period = cyclePeriodMs()
            nextDueAt = lastCastAt + period
            state = State.WAITING
            LogBus.emit("✅ 第 $cycleCount 轮完成，下次 ${period / 60000.0} 分钟后")

            // 【暂时停用】补完就回自由市场等待（同上，代码保留不启用）
            if (false && autoFreeMarket()) {
                LogBus.emit("── 回城模式：进自由市场并走到出口 ──")
                // 显式写 log = ：尾随 lambda 会绑到最后一个参数（leaveAfter），这里不能省
                val (mOk, mMsg) = MarketFlow.enterMarketAndWalkToExit(
                    log = { LogBus.emit("  $it") },
                    leaveAfter = false,   // 回城模式停在出口待命，下一轮到点再出
                )
                inMarket = mOk
                lastResult += if (mOk) "；已回自由市场" else "；回城失败"
                LogBus.emit(if (mOk) "  ↩ $mMsg" else "  ⚠ $mMsg（下一轮按仍在野外处理）")
            }
        } else {
            failStreak++
            lastResult = "本轮 $ok/${enabled.size}"
            if (failStreak >= FAIL_LIMIT) {
                state = State.ERROR
                lastError = "连续 $failStreak 轮失败，已熔断"
                LogBus.emit("🛑 $lastError —— 停止等人工处理")
                stop(lastError)
                state = State.ERROR
            } else {
                // 退避重试：间隔指数增长，绝不死循环猛点
                val backoff = 30_000L * failStreak
                nextDueAt = System.currentTimeMillis() + backoff
                state = State.WAITING
                LogBus.emit("⚠ 本次未全部成功（$failStreak/$FAIL_LIMIT），${backoff / 1000} 秒后重试")
            }
        }
    }

    /**
     * 按第 idx 个技能键（0 基）。
     * 返回 (是否成功, 说明)。输入方式由配置决定。
     */
    private fun pressSkill(idx: Int): Pair<Boolean, String> {
        val b = buffConfig().getOrNull(idx) ?: return false to "配置缺失"
        val c = ctx ?: return false to "上下文未初始化"
        return when (inputMethod()) {
            "touch" -> {
                val pts = OverlayService.pickedPointsOf(c)
                val p = pts.getOrNull(idx) ?: return false to "没有第 ${idx + 1} 个采集点"
                // ★ 必须和悬浮窗「按法」用同一套按压参数。
                //   之前引擎固定用 tapNorm 默认档（90ms/swipe），而用户往往是靠切「按法」
                //   才把点击调通的 —— 结果就是「面板点1 生效、引擎触摸点击无效」。
                val op = c.getSharedPreferences("overlay", Context.MODE_PRIVATE)
                val ms = op.getInt("pressMs", 90)
                val method = op.getString("pressMethod", "swipe") ?: "swipe"
                val r = ShellCore.probe.tapNorm(
                    p.first.toDouble(), p.second.toDouble(), "技能${idx + 1}", ms, method
                )
                if (r.contains("点击")) true to "点击(%.4f, %.4f) $method/${ms}ms".format(p.first, p.second)
                else false to r.take(80)
            }
            else -> {
                val codes = intArrayOf(8, 9, 10, 11)          // KEYCODE_1..4
                val code = codes.getOrElse(b.key) { 8 }
                val r = ShellCore.probe.sendKey(code)
                if (r.contains("已发送")) true to "按键 $code" else false to r.take(80)
            }
        }
    }

    // ------------------------------------------------------------ 上报

    /** 供集控上报的状态快照。 */
    fun statusJson(): JSONObject {
        val o = JSONObject()
        o.put("state", state.name)
        o.put("running", running)
        o.put("cycleCount", cycleCount)
        o.put("failStreak", failStreak)
        o.put("lastError", lastError)
        o.put("lastResult", lastResult)
        o.put("inputMethod", if (ctx == null) "?" else inputMethod())
        o.put("autoFreeMarket", autoFreeMarket())
        o.put("inMarket", inMarket)
        o.put("cyclePeriodMs", cyclePeriodMs())
        if (nextDueAt > 0) o.put("nextDueAt", nextDueAt)
        if (lastCastAt > 0) o.put("lastCastAt", lastCastAt)
        val arr = JSONArray()
        buffConfig().forEach { b ->
            arr.put(JSONObject().apply {
                put("idx", b.idx + 1)
                put("enabled", b.enabled)
                put("key", b.key + 1)
                put("durationMin", b.durMin)
            })
        }
        o.put("buffs", arr)
        return o
    }
}
