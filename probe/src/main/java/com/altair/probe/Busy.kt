package com.altair.probe

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局操作状态
 * ============
 *
 * ## 为什么需要它
 * 之前的问题：点了按钮之后没有任何反馈 —— 用户不知道是在跑、卡住了、还是已经失败，
 * 只能干等。这是设计缺陷，不是小问题。
 *
 * 现在每个操作都走三段式反馈：
 * ```
 * ▸ 采集压测                    开始
 * ⏳ 采集压测  PNG 测试 5/15      进度（可选，长任务才有）
 * ✅ 采集压测 完成 (8.4s)        结果（或 ❌ 失败 + 原因）
 * ```
 *
 * 同时这里维护一个**全局唯一**的"当前在跑什么"，供：
 *   · 主界面常驻状态条（带实时秒数）
 *   · 悬浮窗状态胶囊
 *   · 互斥——上一个没跑完，再点别的会直接提示而不是静默排队
 */
object Busy {

    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()

    @Volatile private var label: String? = null
    @Volatile private var startedAt: Long = 0L

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

    /** 更新当前步骤描述（保持起始时间不变，这样"已用时"是累计的）。 */
    fun step(text: String) {
        if (label == null) return
        label = text
        fire()
    }

    /** 进度形式：`前缀 42%`。 */
    fun percent(prefix: String, pct: Int) = step("$prefix $pct%")

    /** 计数形式：`前缀 3/27`。 */
    fun count(prefix: String, i: Int, n: Int) = step("$prefix $i/$n")

    fun end() {
        label = null
        fire()
    }

    fun elapsedMs(): Long =
        if (label == null) 0L else System.currentTimeMillis() - startedAt

    private fun fire() {
        val v = label
        listeners.toList().forEach { runCatching { it(v) } }
    }
}
