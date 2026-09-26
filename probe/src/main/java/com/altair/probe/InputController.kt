package com.altair.probe

import android.content.Context

/** 模式只在空闲时显式切换；任何动作失败都不会重放到另一输入后端。 */
object InputController {
    enum class Mode(val label: String) { ROOT("Root"), ACCESSIBILITY("无障碍") }
    fun mode(ctx: Context): Mode = if (ctx.getSharedPreferences("input", Context.MODE_PRIVATE)
        .getString("mode", "root") == "accessibility") Mode.ACCESSIBILITY else Mode.ROOT

    fun select(ctx: Context, selected: Mode): Boolean {
        if (Engine.isRunning || Engine.isStopping || Busy.isBusy || OverlayService.picking) return false
        return Actions.whenIdle {
            ctx.getSharedPreferences("input", Context.MODE_PRIVATE).edit()
                .putString("mode", if (selected == Mode.ROOT) "root" else "accessibility").apply()
            LogBus.emit("操作方式已切换为 ${selected.label}；请检查准备情况后手动启动")
        }
    }

    fun readiness(ctx: Context): String? =
        if (mode(ctx) == Mode.ACCESSIBILITY && AccessibilityInputService.connected == null)
            "请在系统设置中开启阿尔泰无障碍服务，并等待连接" else null

    fun status(ctx: Context): String = when (mode(ctx)) {
        Mode.ROOT -> "Root 模式 · ${if (ShellCore.root.isAlive) "已授权" else "待检查授权"}"
        Mode.ACCESSIBILITY -> "无障碍模式 · ${if (AccessibilityInputService.connected != null) "已连接" else "未连接"}"
    }

    fun foregroundPackage(ctx: Context): String = when (mode(ctx)) {
        Mode.ROOT -> ShellCore.probe.foregroundPackage()
        Mode.ACCESSIBILITY -> AccessibilityInputService.connected?.foregroundPackage().orEmpty()
    }

    fun perform(ctx: Context, action: TouchAction, budgetMs: Long, token: Actions.Token, geometry: ScreenGeometry) {
        if (mode(ctx) == Mode.ROOT) {
            ShellCore.probe.perform(action.rootArguments(), budgetMs, token, geometry)
            return
        }
        val service = checkNotNull(AccessibilityInputService.connected) { "无障碍服务未连接" }
        fun checkSafety() {
            token.check()
            check(AccessibilityInputService.connected === service) { "无障碍服务已断开" }
            check(ScreenGeometry.read(ctx) == geometry) { "屏幕发生变化，请重新标记" }
            check(service.foregroundPackage() == OverlayService.targetPkgOf(ctx)) { "目标游戏不在前台" }
        }
        fun pause(ms: Long) {
            val end = System.nanoTime() + ms * 1_000_000
            while (System.nanoTime() < end) { checkSafety(); Thread.sleep(25) }
            checkSafety()
        }
        val runner = AccessibleGestureRunner(service.driver(::checkSafety), ::checkSafety)
        checkSafety()
        runner.perform(action, ::pause)
    }
}
