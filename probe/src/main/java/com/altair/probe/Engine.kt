package com.altair.probe

import android.content.Context
import android.os.SystemClock

/** 独立排期、串行执行；只有 worker 写排期，界面只读取快照。 */
object Engine {
    enum class State { IDLE, WAITING, CASTING, PAUSED, ERROR }
    @Volatile var state = State.IDLE; private set
    @Volatile var nextBuffDueAt = 0L; private set
    @Volatile var nextWalkDueAt = 0L; private set
    @Volatile var buffCastCount = 0; private set
    @Volatile var walkCount = 0; private set
    @Volatile var failStreak = 0; private set
    @Volatile var lastError = ""; private set
    @Volatile var lastResult = ""; private set
    @Volatile var lastWalkResult = ""; private set

    /** 本次启动的时刻（`elapsedRealtime`）。用于在状态栏显示"已运行多久"。 */
    @Volatile var startedAt = 0L; private set
    @Volatile private var running = false
    @Volatile private var worker: Thread? = null
    private var ctx: Context? = null
    const val DEFAULT_DUR_SEC = 280

    /** 两次补 BUFF 之间的等待的默认值（毫秒）。设置页可改。 */
    const val DEFAULT_BUFF_GAP_MS = 1500L

    /** 可填范围。下限不是 0：再短就等于没有间隔，后一次点击会被游戏吞掉。 */
    const val MIN_BUFF_GAP_MS = 100L
    const val MAX_BUFF_GAP_MS = 10_000L

    /**
     * 补 BUFF 与走位之间的等待下限（毫秒）。
     *
     * 实际取值是 `max(它, 补BUFF间隔)` —— 用户把补 BUFF 间隔调大，说明他那款游戏的施法动画
     * 就是长，那么"补 BUFF → 走位"同样需要那么长，不能还停在 1 秒上。
     */
    private const val SEQUENCE_GAP_MIN_MS = 1000L

    private fun prefs() = ctx?.getSharedPreferences("buff", Context.MODE_PRIVATE)

    /**
     * 两次补 BUFF 之间的等待。
     *
     * 技能点击不是"发出去就到"的：游戏要放完上一个技能的施法动画才会接受下一次输入。
     * 间隔太短的话后一次点击会被游戏**吞掉**，而注入层看到的是"注入成功" ——
     * 于是日志上一切正常、游戏里只上了一个 BUFF，属于最难查的那类失败。
     * 所以这个值必须让用户能按自己那款游戏调。
     */
    var buffGapMs: Long
        get() = prefs()?.getLong("buffGapMs", DEFAULT_BUFF_GAP_MS)
            ?.coerceIn(MIN_BUFF_GAP_MS, MAX_BUFF_GAP_MS) ?: DEFAULT_BUFF_GAP_MS
        set(v) {
            prefs()?.edit()?.putLong("buffGapMs", v.coerceIn(MIN_BUFF_GAP_MS, MAX_BUFF_GAP_MS))?.apply()
        }
    val isRunning: Boolean get() = running
    val isStopping: Boolean get() = !running && worker != null
    data class BuffSlot(val idx: Int, val enabled: Boolean, val durSec: Int)

    fun init(context: Context) {
        ctx = context.applicationContext
        ShellCore.init(context)
        WalkFlow.init(context)
    }

    fun buffConfig(): List<BuffSlot> {
        val sp = ctx?.getSharedPreferences("buff", Context.MODE_PRIVATE) ?: return emptyList()
        return (0 until Picks.SKILL_COUNT).map { i ->
            val seconds = if (sp.contains("durSec$i")) sp.getInt("durSec$i", DEFAULT_DUR_SEC)
                else (sp.getInt("dur$i", 0).toLong() * 60).takeIf { it > 0 }?.coerceAtMost(86_400)?.toInt()
                    ?: DEFAULT_DUR_SEC
            BuffSlot(i, sp.getBoolean("enabled$i", i == 0), seconds.coerceIn(1, 86_400))
        }
    }
    fun slotPeriodMs(durSec: Int): Long = durSec * 1000L

    @Synchronized fun start(context: Context) {
        if (running || worker != null || Actions.busy || Busy.isBusy || OverlayService.picking) {
            LogBus.emit("请等待当前动作完全停止后再启动")
            return
        }
        // ★ 闸门关闭时**连 worker 都不启动**：未确认松手之后，任务每跑一轮都会注入触摸，
        // 而触摸状态是未知的。在这里拦住的代价最小，也说得出原因（比"启动了但一动不动"强）。
        Actions.gate.blockReason()?.let {
            state = State.ERROR
            lastError = it
            LogBus.emit("🛑 无法启动：$it")
            return
        }
        init(context)
        InputController.readiness(context)?.let {
            state = State.ERROR
            lastError = it
            LogBus.emit(it)
            return
        }
        val need = Picks.required(context)
        val missing = need.filter { Picks.get(context, it) == null }
        if (missing.isNotEmpty()) {
            state = State.ERROR
            lastError = "请先标记：${missing.joinToString("、") { Picks.label(it) }}"
            LogBus.emit(lastError)
            return
        }
        // ★ 几何也必须在**点击这一刻**校验。
        // 曾经只校验"有没有标"，于是先报「✅ 任务已启动」，紧接着 worker 第一轮抛
        // 「请在当前游戏画面重新标记」把任务停掉 —— 在用户看来就是"点启动立马停止"，
        // 像是按钮坏了。校验放在这里，失败原因当场就说清楚。
        Picks.geometryProblem(context, need)?.let {
            state = State.ERROR
            lastError = it
            LogBus.emit(lastError)
            return
        }
        if (!android.provider.Settings.canDrawOverlays(context)) {
            state = State.ERROR
            lastError = "请先开启悬浮窗权限，以便在游戏中停止任务"
            LogBus.emit(lastError)
            return
        }
        OverlayService.start(context)
        running = true
        startedAt = SystemClock.elapsedRealtime()
        state = State.WAITING
        lastError = ""
        lastResult = "等待目标游戏进入前台"
        lastWalkResult = ""
        buffCastCount = 0
        walkCount = 0
        failStreak = 0
        worker = Thread({ loop(context.applicationContext) }, "automation").apply { isDaemon = true; start() }
        LogBus.emit(
            "任务启动（${InputController.mode(context).label}）：首次立即执行，之后按各自间隔执行" +
                if (Picks.jumpReady(context)) "；走位回位后等待 1 秒跳一次"
                else "；跳跃未标记，走位照走但不跳（可在悬浮窗补标）"
        )
    }

    @Synchronized fun stop(reason: String = "手动停止") {
        running = false
        startedAt = 0L
        Actions.cancel()
        worker?.interrupt()
        state = State.IDLE
        nextBuffDueAt = 0
        nextWalkDueAt = 0
        lastResult = reason
        LogBus.emit("任务停止：$reason")
    }

    private fun checkRunning() {
        if (!running || Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    /**
     * 按 [ActionPacer] 的裁决睡够间隔。
     *
     * ★ 分片睡（最多 200ms 一片）而不是一次睡满：一次睡 1.5 秒会让「停止任务」最多迟钝
     * 1.5 秒才响应，用户会以为按钮没生效、然后连点。分片后停止几乎是立刻生效。
     */
    private fun ActionPacer.await(kind: Int) {
        while (true) {
            checkRunning()
            val delay = delayBefore(kind, SystemClock.elapsedRealtime())
            if (delay <= 0) return
            Thread.sleep(minOf(delay, 200L))
        }
    }

    private fun loop(c: Context) {
        val skills = Array(Picks.SKILL_COUNT) { Schedule() }
        val walk = Schedule()
        // 动作之间的间隔规则集中在这里：补 BUFF 之间等施法动画，补 BUFF 与走位之间留得更足。
        // 具体数值与理由见 [BUFF_GAP_MS] / [SEQUENCE_GAP_MS]。
        val pacer = ActionPacer(buffGapMs, ActionPacer.switchGapFor(buffGapMs, SEQUENCE_GAP_MIN_MS))
        try {
            while (running) {
                InputController.readiness(c)?.let { error(it) }
                val now = SystemClock.elapsedRealtime()
                val config = buffConfig()
                config.forEach { skills[it.idx].configure(if (it.enabled) slotPeriodMs(it.durSec) else 0L, now) }
                // 走位开关关掉时把周期配成 0：Schedule 对周期 0 一律 !ready、dueAt 也是 0，
                // 于是既不排期也不显示倒计时，整条走位路径都不会被走到。
                walk.configure(if (WalkFlow.enabled) WalkFlow.intervalMs else 0L, now)
                // 每轮把用户设置读进来：改完间隔不必重启任务，下一次动作就按新值走。
                // pacer 本身要留住（它记着"上一次动作何时结束"），所以只改数值不重建。
                val gap = buffGapMs
                pacer.setSameKindGapMs(gap)
                pacer.setSwitchKindGapMs(ActionPacer.switchGapFor(gap, SEQUENCE_GAP_MIN_MS))
                nextBuffDueAt = skills.map { it.dueAt() }.filter { it > 0 }.minOrNull() ?: 0
                nextWalkDueAt = walk.dueAt()
                if (Actions.busy || (!walk.ready(now) && skills.none { it.ready(now) })) {
                    Thread.sleep(200)
                    continue
                }
                // 闸门可能在任务运行期间被拉上（动作层发现未确认松手）——此时立即退出循环，
                // 不要等到下一个 ready 的动作再撞一次墙。
                Actions.gate.blockReason()?.let { reason ->
                    state = State.ERROR
                    lastError = reason
                    LogBus.emit("🛑 任务停止：$reason")
                    return
                }
                if (InputController.foregroundPackage(c) != OverlayService.targetPkgOf(c)) {
                    state = State.PAUSED
                    lastResult = "等待目标游戏进入前台"
                    Thread.sleep(1500)
                    continue
                }
                checkRunning()
                state = State.WAITING
                Picks.requireGeometry(c, Picks.required(c))

                // ---- 补 BUFF：间隔由 pacer 统一裁决 ----
                for (slot in config.filter { it.enabled }) {
                    checkRunning()
                    val schedule = skills[slot.idx]
                    if (!schedule.ready(SystemClock.elapsedRealtime())) continue
                    pacer.await(ActionPacer.BUFF)
                    state = State.CASTING
                    val result = Picks.tap(c, Picks.SKILLS[slot.idx])
                    checkRunning()
                    pacer.done(ActionPacer.BUFF, SystemClock.elapsedRealtime())
                    val resultText = result.describe(Picks.label(Picks.SKILLS[slot.idx]))
                    lastResult = resultText
                    LogBus.emit(resultText)
                    if (result.isOk) {
                        schedule.success(SystemClock.elapsedRealtime())
                        buffCastCount++
                        lastError = ""      // 成功后清掉上一次的失败文案，免得状态栏一直挂着旧错误
                    } else {
                        schedule.failure(SystemClock.elapsedRealtime())
                        lastError = result.message
                        // 危险结局（未确认松手）不走熔断计数，直接停：触摸状态未知时
                        // 再补两次技能只会让事情更糟。闸门已经拉上，这里负责把话说清楚。
                        check(!result.isDangerous) {
                            "触摸状态未知（未确认松手），任务已停止：${result.message}"
                        }
                        check(schedule.failures < 3) { "${Picks.label(Picks.SKILLS[slot.idx])}连续失败 3 次，任务停止" }
                    }
                    failStreak = skills.maxOf { it.failures }
                }

                // ---- 走位（开关关掉时 walk 周期为 0，这里永远不会进来）----
                checkRunning()
                if (walk.ready(SystemClock.elapsedRealtime())) {
                    // 与上一次补 BUFF 之间留足间隔：摇杆的 DOWN 撞在施法动画上会被吞，
                    // 出现"人没走、却报走位完成"。反向（走位→补 BUFF）同样由 pacer 兜住。
                    pacer.await(ActionPacer.WALK)
                    state = State.CASTING
                    val result = WalkFlow.strollAndJump { LogBus.emit(it) }
                    checkRunning()
                    pacer.done(ActionPacer.WALK, SystemClock.elapsedRealtime())
                    val resultText = result.describe("走位与跳跃")
                    lastWalkResult = resultText
                    LogBus.emit(resultText)
                    // 部分走位失败后位置未知，不能从新起点自动重放整个往返。
                    // 未确认松手同样在这里停下（而且闸门已拉上，不会再有下一次注入）。
                    check(result.isOk) { resultText }
                    walk.success(SystemClock.elapsedRealtime())
                    walkCount++
                }
                state = State.WAITING
            }
        } catch (_: InterruptedException) {
            // 用户停止，后续动作不再执行。
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            state = State.ERROR
            LogBus.emit("任务已停止：$lastError")
        } finally {
            synchronized(this) {
                running = false
                startedAt = 0L
                worker = null
                nextBuffDueAt = 0
                nextWalkDueAt = 0
                if (state != State.ERROR) state = State.IDLE
            }
        }
    }

    /** 本次已运行多久（毫秒）；没在跑返回 0。 */
    fun runElapsedMs(): Long =
        if (!running || startedAt <= 0) 0L else SystemClock.elapsedRealtime() - startedAt

    fun countdown(at: Long): String = if (!running || at <= 0) "—"
        else Ui.mmss(at - SystemClock.elapsedRealtime(), true)
    fun stateText(): String = when {
        isStopping -> "正在停止…"
        state == State.ERROR -> "已停止 · ${lastError.take(70)}"
        state == State.PAUSED -> "等待游戏前台"
        state == State.CASTING -> "正在执行动作"
        running -> "运行中"
        else -> "未启动"
    }
}
