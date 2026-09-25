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
    @Volatile private var running = false
    @Volatile private var worker: Thread? = null
    private var ctx: Context? = null
    const val DEFAULT_DUR_SEC = 280
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
        init(context)
        val missing = Picks.required(context).filter { Picks.get(context, it) == null }
        if (missing.isNotEmpty()) {
            state = State.ERROR
            lastError = "请先标记：${missing.joinToString("、") { Picks.label(it) }}"
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
        state = State.WAITING
        lastError = ""
        lastResult = "等待目标游戏进入前台"
        lastWalkResult = ""
        buffCastCount = 0
        walkCount = 0
        failStreak = 0
        worker = Thread({ loop(context.applicationContext) }, "automation").apply { isDaemon = true; start() }
        LogBus.emit(
            "任务启动：首次立即执行，之后按各自间隔执行" +
                if (Picks.jumpReady(context)) "；走位回位后等待 1 秒跳一次"
                else "；跳跃未标记，走位照走但不跳（可在悬浮窗补标）"
        )
    }

    @Synchronized fun stop(reason: String = "手动停止") {
        running = false
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

    private fun loop(c: Context) {
        val skills = Array(Picks.SKILL_COUNT) { Schedule() }
        val walk = Schedule()
        try {
            while (running) {
                val now = SystemClock.elapsedRealtime()
                val config = buffConfig()
                config.forEach { skills[it.idx].configure(if (it.enabled) slotPeriodMs(it.durSec) else 0L, now) }
                walk.configure(WalkFlow.intervalMs, now)
                nextBuffDueAt = skills.map { it.dueAt() }.filter { it > 0 }.minOrNull() ?: 0
                nextWalkDueAt = walk.dueAt()
                if (Actions.busy || (!walk.ready(now) && skills.none { it.ready(now) })) {
                    Thread.sleep(200)
                    continue
                }
                if (ShellCore.probe.foregroundPackage() != OverlayService.targetPkgOf(c)) {
                    state = State.PAUSED
                    lastResult = "等待目标游戏进入前台"
                    Thread.sleep(1500)
                    continue
                }
                checkRunning()
                state = State.WAITING
                Picks.requireGeometry(c, Picks.required(c))
                for (slot in config.filter { it.enabled }) {
                    checkRunning()
                    val schedule = skills[slot.idx]
                    if (!schedule.ready(SystemClock.elapsedRealtime())) continue
                    state = State.CASTING
                    val result = Picks.tap(c, Picks.SKILLS[slot.idx])
                    checkRunning()
                    lastResult = result.second
                    LogBus.emit(result.second)
                    if (result.first) {
                        schedule.success(SystemClock.elapsedRealtime())
                        buffCastCount++
                        lastError = ""      // 成功后清掉上一次的失败文案，免得状态栏一直挂着旧错误
                    } else {
                        schedule.failure(SystemClock.elapsedRealtime())
                        lastError = result.second
                        check(schedule.failures < 3) { "${Picks.label(Picks.SKILLS[slot.idx])}连续失败 3 次，任务停止" }
                    }
                    failStreak = skills.maxOf { it.failures }
                    Thread.sleep(1500)
                }
                checkRunning()
                if (walk.ready(SystemClock.elapsedRealtime())) {
                    state = State.CASTING
                    val result = WalkFlow.strollAndJump { LogBus.emit(it) }
                    checkRunning()
                    lastWalkResult = result.second
                    LogBus.emit(result.second)
                    // 部分走位失败后位置未知，不能从新起点自动重放整个往返。
                    check(result.first) { result.second }
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
                worker = null
                nextBuffDueAt = 0
                nextWalkDueAt = 0
                if (state != State.ERROR) state = State.IDLE
            }
        }
    }

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
