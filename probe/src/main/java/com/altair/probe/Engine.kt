package com.altair.probe

import android.content.Context
import java.util.Locale

/**
 * 挂机引擎
 * ========
 *
 * 只做两件事，而且是**两套互相独立的计时器**（用户指定）：
 * ```
 *   ① 补 BUFF：4 个槽位各自计时，到点就点对应的技能图标
 *              槽位周期 = 该槽填的时长 × 0.94（留 6% 余量吸收抖动与卡顿）
 *   ② 原地走位：独立的间隔计时器（默认 15 分钟），到点走 1:2:1 三段 + 跳一下
 * ```
 * 两者**启动时都立即执行一次**（用户要求）：点完启动马上能看到动作，
 * 而不是干等 15 分钟看不出到底有没有生效。
 *
 * ## 启动那一刻的执行顺序
 * 两者同时到点，实际顺序是**先补 BUFF、再走位** —— 补 BUFF 是主功能，先把它做掉；
 * 即使随后走位出问题，BUFF 也已经补上了。
 *
 * ## 两条安全约束（沿用历史设计，都是踩过坑换来的）
 * 1. **前台门禁**：只有目标游戏在前台才动手。无人值守时最危险的失败是"在错误的界面上乱点"。
 * 2. **失败熔断**：同一个槽位连续失败 3 次就停下等人工，绝不死循环猛点。
 *
 * ## 输入通道
 * 只剩触摸这一条。技能图标 / 跳跃 / 轮盘的坐标全部来自 [Picks]（用户手动标注）。
 * 键盘通道（`input keyevent`）已随简化删除 —— 数字键在野外实测无响应，留着只是复杂度。
 */
object Engine {

    enum class State { IDLE, WAITING, CASTING, PAUSED, ERROR }

    @Volatile var state: State = State.IDLE
        private set

    /** 下一个 BUFF 到点时刻（4 个槽位里最早的），供界面倒计时。 */
    @Volatile var nextBuffDueAt: Long = 0L
        private set

    /** 下一次原地走位到点时刻，供界面倒计时。 */
    @Volatile var nextWalkDueAt: Long = 0L
        private set

    /** 累计补 BUFF 次数（按槽位计，不是"轮"）。 */
    @Volatile var buffCastCount: Int = 0
        private set

    /** 累计走位次数。 */
    @Volatile var walkCount: Int = 0
        private set

    /** 当前连续失败次数（取所有槽位的最大值）。 */
    @Volatile var failStreak: Int = 0
        private set

    @Volatile var lastError: String = ""
        private set

    @Volatile var lastResult: String = ""
        private set

    @Volatile var lastWalkResult: String = ""
        private set

    private var ctx: Context? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    /** 连续失败多少次就熔断。 */
    private const val FAIL_LIMIT = 3

    /** BUFF 之间的排队间隔：槽位同时到点时，别一瞬间点完 4 个（用户指定 1.5 秒）。 */
    private const val BUFF_GAP_MS = 1500L

    /** 前台不是游戏时的重看间隔（不是错误，只是时机不对）。 */
    private const val FORE_SKIP_RETRY_MS = 30_000L

    /** 还没跑过任何动作时的短重试间隔（用户正在切回游戏）。 */
    private const val FIRST_RETRY_MS = 5_000L

    /** 走位失败后的重试间隔（不必等一整个 15 分钟）。 */
    private const val WALK_RETRY_MS = 60_000L

    /** 默认 BUFF 时长（秒）。 */
    const val DEFAULT_DUR_SEC = 280

    /** 每槽位下一次到点时刻（下标 = 槽位 0..3）。0 表示未排期。 */
    private val dueAt = LongArray(Picks.SKILL_COUNT)

    /** 每槽位连续失败次数。 */
    private val failAt = IntArray(Picks.SKILL_COUNT)

    private var walkDueAt = 0L

    /** 全局静默截止时刻（前台门禁跳过时用）。 */
    private var retryAt = 0L

    // ------------------------------------------------------------ 配置读取

    private fun buffPrefs(): android.content.SharedPreferences? =
        ctx?.getSharedPreferences("buff", Context.MODE_PRIVATE)

    data class BuffSlot(val idx: Int, val enabled: Boolean, val durSec: Int)

    /**
     * BUFF 配置（4 个槽位）。
     *
     * 时长单位是**秒**。键名用 `durSec$i` 而不是复用 `dur$i` —— 旧键存的是**分钟**，
     * 复用同一个键会把"5 分钟"读成"5 秒"（这种单位串台比报错更难查）。
     * 旧值做一次迁移：`dur$i`（分钟）× 60。
     *
     * 上下文没注入时返回空列表，**绝不抛异常** —— 界面启动时就要读它，
     * 这里一崩就是整个 App 崩（后台线程里的未捕获异常会带走进程）。
     */
    fun buffConfig(): List<BuffSlot> {
        val sp = buffPrefs() ?: return emptyList()
        return (0 until Picks.SKILL_COUNT).map { i ->
            val sec = sp.getInt("durSec$i", -1).let { v ->
                if (v > 0) v else sp.getInt("dur$i", 0).takeIf { it > 0 }?.times(60) ?: DEFAULT_DUR_SEC
            }
            BuffSlot(
                idx = i,
                // 默认只勾第一个：避免装好后没配就一口气点 4 个技能
                enabled = sp.getBoolean("enabled$i", i == 0),
                durSec = sec.coerceIn(10, 86_400)
            )
        }
    }

    /** 单个槽位的补 BUFF 周期：时长 × 0.94。 */
    fun slotPeriodMs(durSec: Int): Long = (durSec * 1000L * 0.94).toLong()

    // ------------------------------------------------------------ 生命周期

    /**
     * 注入上下文。**界面 onCreate 时就要调**。
     *
     * 为什么必须提前调用：引擎没启动时也有代码要读配置（主界面刷新状态的那几行），
     * 而 `pm install -r` 会重启进程、此时 ctx 还是 null —— 后台线程里的 NPE 会直接
     * 带走整个 App，用户看到的就是「更新后一打开就闪退」。
     */
    fun init(context: Context) {
        ctx = context.applicationContext
        WalkFlow.init(context)
    }

    fun start(context: Context) {
        if (running) {
            LogBus.emit("引擎已在运行")
            return
        }
        init(context)

        val enabled = buffConfig().filter { it.enabled }
        if (enabled.isEmpty()) {
            state = State.ERROR
            lastError = "没有启用任何 BUFF"
            LogBus.emit("⛔ 无法启动：4 个 BUFF 槽位一个都没勾选。请到「设置」页勾选要补的技能。")
            return
        }
        if (!Picks.skillsReady(context)) {
            val miss = Picks.missingSkills(context).joinToString("、")
            state = State.ERROR
            lastError = "技能图标没标注全（缺 $miss）"
            LogBus.emit(
                "⛔ 无法启动：技能图标还没标注全（缺 $miss）。\n" +
                    "   请点「启动悬浮窗」→ 切到游戏 → 依次点「标技能1..4」并点画面上对应的技能图标。"
            )
            return
        }

        running = true
        failStreak = 0
        for (i in 0 until Picks.SKILL_COUNT) {
            failAt[i] = 0
            dueAt[i] = 0L
        }
        lastError = ""
        lastResult = ""
        retryAt = 0

        // 启动立即执行一次：两点都设成"现在"
        val now = System.currentTimeMillis()
        for (s in enabled) dueAt[s.idx] = now
        walkDueAt = now
        nextBuffDueAt = now
        nextWalkDueAt = now
        state = State.WAITING

        LogBus.emit(
            "▶ 引擎启动：补 BUFF ${enabled.size} 个（" +
                enabled.joinToString("、") { "BUFF${it.idx + 1} 每 ${it.durSec} 秒" } +
                "）；走位每 ${WalkFlow.intervalMin} 分钟一次 —— 两者都立即跑第一轮"
        )
        if (Picks.get(context, Picks.JUMP) == null) {
            LogBus.emit("ℹ 「跳跃」还没标注：走位会走完三段，但**不跳**。要跳请在悬浮窗点「标跳跃」。")
        }
        if (!Picks.joystickAnnotated(context)) {
            LogBus.emit("ℹ 「轮盘中心」还没标注：走位先用默认左下角位置。建议点悬浮窗「标轮盘」校准。")
        }

        worker = Thread { loop() }.apply { isDaemon = true; name = "engine" }
        worker?.start()
    }

    fun stop(reason: String = "手动停止") {
        if (!running) return
        running = false
        worker?.interrupt()
        worker = null
        state = State.IDLE
        nextBuffDueAt = 0
        nextWalkDueAt = 0
        LogBus.emit("⏹ 引擎停止（$reason）")
    }

    val isRunning: Boolean get() = running

    // ------------------------------------------------------------ 主循环

    private fun loop() {
        while (running) {
            try {
                Thread.sleep(500)
                if (!running) break

                val now = System.currentTimeMillis()
                if (retryAt > 0 && now < retryAt) continue

                val c = ctx ?: continue
                val target = OverlayService.targetPkgOf(c)

                // 门禁：只有目标游戏在前台才动手
                val fg = runCatching { ShellCore.probe.foregroundPackage() }.getOrDefault("")
                if (fg != target) {
                    // 还没执行过任何动作时用短重试：用户多半是"在本页点了启动、正要切回游戏"，
                    // 让切回去之后立刻就能跑，而不是干等半分钟。
                    val wait = if (buffCastCount == 0 && walkCount == 0) FIRST_RETRY_MS else FORE_SKIP_RETRY_MS
                    lastResult = "跳过：前台是「${fg.ifBlank { "未知" }}」，不是 $target"
                    LogBus.emit("⏭ $lastResult（${wait / 1000} 秒后再看）")
                    retryAt = now + wait
                    state = State.WAITING
                    continue
                }
                retryAt = 0

                castDueBuffs(now)
                walkIfDue(now)
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                LogBus.emit("引擎循环异常：$lastError")
                Thread.sleep(3000)
            }
        }
    }

    // ------------------------------------------------------------ 补 BUFF

    /**
     * 把所有已到点的槽位补掉。
     *
     * 槽位**各自独立计时**：BUFF1 时长 280 秒、BUFF2 时长 600 秒时，两者互不干扰
     * （这正是"各自独立时长"的意义 —— 统一按最短时长补，长 BUFF 会被过量重放）。
     */
    private fun castDueBuffs(now: Long) {
        val c = ctx ?: return
        val enabled = buffConfig().filter { it.enabled }
        val due = enabled.filter { dueAt[it.idx] > 0 && dueAt[it.idx] <= now }
        if (due.isEmpty()) return

        state = State.CASTING
        LogBus.emit("── 补 BUFF：${due.size} 个到点（${due.joinToString("、") { "BUFF${it.idx + 1}" }}）──")

        for ((n, s) in due.withIndex()) {
            if (!running) return
            val r = Picks.tap(c, Picks.SKILLS[s.idx], "BUFF${s.idx + 1}")
            val nowMs = System.currentTimeMillis()
            if (r.first) {
                failAt[s.idx] = 0
                buffCastCount++
                dueAt[s.idx] = nowMs + slotPeriodMs(s.durSec)
                LogBus.emit(
                    "  ✅ BUFF${s.idx + 1}（技能图标 ${s.idx + 1}）已点，" +
                        "下次 ${"%.1f".format(Locale.US, slotPeriodMs(s.durSec) / 60_000.0)} 分钟后"
                )
            } else {
                failAt[s.idx]++
                failStreak = failAt.maxOrNull() ?: 0
                val backoff = 30_000L * failAt[s.idx]
                dueAt[s.idx] = nowMs + backoff
                lastError = "BUFF${s.idx + 1} 点击失败：${r.second}"
                LogBus.emit("  ❌ BUFF${s.idx + 1} 失败（第 ${failAt[s.idx]}/$FAIL_LIMIT 次）：${r.second}")
                if (failAt[s.idx] >= FAIL_LIMIT) {
                    lastError = "BUFF${s.idx + 1} 连续 $FAIL_LIMIT 次失败，已熔断"
                    LogBus.emit("🛑 $lastError —— 停止等人工处理")
                    stop(lastError)
                    state = State.ERROR
                    return
                }
                LogBus.emit("     ${backoff / 1000} 秒后单独重试该槽位")
            }
            // 排队释放：槽位之间固定 1.5 秒（最后一个不用等，别拖住后面的走位）
            if (n < due.size - 1) {
                try {
                    Thread.sleep(BUFF_GAP_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        refreshNextBuffDue()
        lastResult = "补 BUFF 完成（累计 $buffCastCount 次）"
        if (state != State.ERROR) state = State.WAITING
    }

    /** 重算"下一个到点的 BUFF"，供界面倒计时。 */
    private fun refreshNextBuffDue() {
        val enabled = buffConfig().filter { it.enabled }
        nextBuffDueAt = enabled.map { dueAt[it.idx] }.filter { it > 0 }.minOrNull() ?: 0L
    }

    // ------------------------------------------------------------ 原地走位

    private fun walkIfDue(now: Long) {
        if (walkDueAt <= 0 || walkDueAt > now) return
        state = State.CASTING
        LogBus.emit("── 原地走位（第 ${walkCount + 1} 次）──")
        val (ok, msg) = WalkFlow.strollAndJump { LogBus.emit(it) }
        walkCount++
        lastWalkResult = msg
        val nowMs = System.currentTimeMillis()
        walkDueAt = nowMs + if (ok) WalkFlow.intervalMs else WALK_RETRY_MS
        nextWalkDueAt = walkDueAt
        if (ok) {
            LogBus.emit("  ✅ $msg")
            LogBus.emit("     下次走位：${WalkFlow.intervalMin} 分钟后")
        } else {
            LogBus.emit("  ⚠ $msg（${WALK_RETRY_MS / 1000} 秒后重试，不等一整个间隔）")
        }
        if (state != State.ERROR) state = State.WAITING
    }

    // ------------------------------------------------------------ 展示辅助

    /** 倒计时文案，供主界面与悬浮窗共用。 */
    fun countdown(at: Long): String {
        if (!running || at <= 0) return "—"
        val left = at - System.currentTimeMillis()
        if (left <= 0) return "即将执行"
        val sec = left / 1000
        return if (sec < 60) "${sec} 秒后" else "%d分%02d秒后".format(sec / 60, sec % 60)
    }

    /** 状态中文名。 */
    fun stateText(): String = when (state) {
        State.IDLE -> "空闲（未启动）"
        State.WAITING -> "运行中 · 等待到点"
        State.CASTING -> "正在执行动作…"
        State.PAUSED -> "已暂停"
        State.ERROR -> "出错已熔断（需人工）"
    }
}
