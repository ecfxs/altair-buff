package com.altair.probe

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局操作状态（"现在在跑什么"）
 * ==============================
 *
 * ## 为什么需要它
 * 以前点了按钮之后没有任何反馈 —— 用户不知道是在跑、卡住了、还是已经失败，只能干等。
 * 这是设计缺陷，不是小问题。长耗时操作（自更新要下载几十 MB）尤其明显。
 *
 * 现在每个操作都走两段式反馈：
 * ```
 * ▸ 下载并更新              开始（Busy.begin）→ 底部操作条立刻变成"⏳ 下载并更新 3 秒"
 * ✅ 下载并更新 完成         结果（Busy.end + 带时间戳写进日志页）
 * ```
 * 中间的细粒度进度（例如"下载 42%"）由操作自己 `LogBus.emit`，日志页里看得到 ——
 * 那些数字变化太快，放在状态条上只会闪。
 *
 * 同时这里维护一个**全局唯一**的"当前在跑什么"，供：
 *   · 主界面底部常驻状态条（带实时秒数，见 `MainActivity.bottomBar`）
 *   · 互斥 —— 上一个没跑完，再点别的会直接提示而不是静默排队
 *
 * ★ 订阅者必须真的有人。这个类曾经留着 `add()` 却没有任何调用方，
 *   于是 `begin()` 只是把状态写进一个没人看的字段 —— 反馈等于没有。界面改版时别再漏掉订阅。
 */
object Busy {

    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()

    @Volatile private var label: String? = null
    @Volatile private var startedAt: Long = 0L

    /** 当前正在跑的操作名；空闲为 null。 */
    val current: String? get() = label

    val isBusy: Boolean get() = label != null

    fun add(l: (String?) -> Unit) {
        listeners.add(l)
        l(label)                      // 立即回放当前状态，避免订阅者显示过期内容
    }

    fun remove(l: (String?) -> Unit) {
        listeners.remove(l)
    }

    /** 开始一个操作。 */
    fun begin(text: String) {
        label = text
        startedAt = System.currentTimeMillis()
        fire()
    }

    fun end() {
        label = null
        startedAt = 0L
        fire()
    }

    /** 当前操作已用时（毫秒）；空闲为 0。 */
    fun elapsedMs(): Long =
        if (label == null) 0L else System.currentTimeMillis() - startedAt

    private fun fire() {
        val v = label
        listeners.toList().forEach { runCatching { it(v) } }
    }
}
