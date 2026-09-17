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

    private fun buffPrefs(): android.content.SharedPreferences =
        ctx!!.getSharedPreferences("buff", Context.MODE_PRIVATE)

    data class BuffCfg(val idx: Int, val enabled: Boolean, val key: Int, val durMin: Int)

    fun buffConfig(): List<BuffCfg> {
        val sp = buffPrefs()
        return (0 until 3).map { i ->
            BuffCfg(
                idx = i,
                enabled = sp.getBoolean("enabled$i", i == 0),
                key = sp.getInt("key$i", i),
                durMin = sp.getInt("dur$i", 5)
            )
        }
    }

    private fun inputMethod(): String =
        ctx!!.getSharedPreferences("overlay", Context.MODE_PRIVATE)
            .getString("inputMethod", "keyevent") ?: "keyevent"

    /** 循环周期 = 最短 BUFF 时长 × 0.94（留 6% 余量吸收抖动与卡顿）。 */
    fun cyclePeriodMs(): Long {
        val d = buffConfig().filter { it.enabled }.minOfOrNull { it.durMin } ?: 0
        return if (d <= 0) 0L else (d * 60_000L * 0.94).toLong()
    }

    // ------------------------------------------------------------ 生命周期

    fun start(context: Context) {
        if (running) { LogBus.emit("引擎已在运行"); return }
        ctx = context.applicationContext
        val period = cyclePeriodMs()
        if (period <= 0) {
            LogBus.emit("⛔ 无法启动：没有任何 BUFF 被启用，或时长未填。请到「设置」页配置。")
            state = State.ERROR; lastError = "没有启用任何 BUFF"
            return
        }
        val touch = inputMethod() == "touch"
        if (touch && OverlayService.pickedPointsOf(ctx!!).size < 4) {
            LogBus.emit("⛔ 无法启动：输入方式为触摸，但还没采集技能键坐标（需 4 个点）。")
            state = State.ERROR; lastError = "触摸方式但缺技能键坐标"
            return
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
        return when (inputMethod()) {
            "touch" -> {
                val pts = OverlayService.pickedPointsOf(ctx!!)
                val p = pts.getOrNull(idx) ?: return false to "没有第 ${idx + 1} 个采集点"
                val r = ShellCore.probe.tapNorm(p.first.toDouble(), p.second.toDouble(), "技能${idx + 1}")
                if (r.contains("点击")) true to "点击(%.4f, %.4f)".format(p.first, p.second)
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
