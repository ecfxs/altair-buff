package com.altair.probe

/**
 * 会话级注入闸门
 * ==============
 *
 * 记一件事：**这台设备现在敢不敢再注入触摸**。
 *
 * ## 为什么需要"会话级"的锁
 * 单次失败是可以重试的：注入被拒就再发一遍，超时就重来一轮。但有一类结果不能靠重试解决 ——
 * "已按下、但系统没确认松手"（见 [ActionFailure.Fault.RELEASE_UNKNOWN]）。这时屏幕上可能
 * 还按着一根手指，位置和时长都不受控。此刻继续自动注入的后果不是"这次失败"，而是
 * **后续每一次点击都可能落在错误的位置**：摇杆卡死、技能打到别人身上、把面板点没了。
 *
 * 所以在会话级别拉闸：
 *   - 一旦出现未确认松手 → 锁上，**所有**走 [Actions.run] 的注入动作在入口就被挡住；
 *   - 用户停止任务只是取消当前动作，**不会**解锁（停止 ≠ 触摸状态恢复）；
 *   - 只有用户明确确认（"我已确认游戏里的触摸已复位"）才解锁 —— 见 [clear]。
 *
 * ## 为什么不直接杀掉进程重来
 * 锁是进程内的。进程被杀掉再启动，锁自然就没了 —— 这一点刻意保留：重启应用本身就意味着
 * 用户重新介入了一次，比一个自动重启更可信。但**不能**用"自动重启"来悄悄清锁，那等于没锁。
 */
class InjectGate {

    @Volatile
    var locked: Boolean = false
        private set

    @Volatile
    var lastMessage: String = ""
        private set

    /** 拉闸。返回 true 表示"这次真的由我拉上的"（用于避免重复刷日志）。 */
    fun lock(message: String): Boolean = synchronized(this) {
        val first = !locked
        locked = true
        lastMessage = message
        first
    }

    /** 用户确认触摸状态已复位；解除 [lock]，并清掉上一次的说明。 */
    fun clear() = synchronized(this) {
        locked = false
        lastMessage = ""
    }

    /** 闸门关闭时给用户看的一句话；开着返回 null。 */
    fun blockReason(): String? =
        if (!locked) null
        else "检测到未确认松手：触摸状态未知，已阻止继续自动注入。" +
            "请在游戏里确认角色松开摇杆/按键不再被按下后，点击这里解除阻止"

    /** 界面/日志用的一行状态说明。 */
    fun describe(): String =
        if (!locked) "注入闸门：正常"
        else "注入闸门：已阻止自动注入（$lastMessage）"
}
