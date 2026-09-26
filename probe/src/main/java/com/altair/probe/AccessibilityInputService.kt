package com.altair.probe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent

/** 只读取窗口所属包名；不记录控件文本，不自动启动任务。 */
class AccessibilityInputService : AccessibilityService() {
    companion object {
        @Volatile var connected: AccessibilityInputService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        connected = this
        LogBus.emit("无障碍服务已连接，请手动启动任务")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        if (InputController.mode(this) == InputController.Mode.ACCESSIBILITY) Engine.stop("无障碍服务被中断")
    }

    private fun disconnected() {
        if (connected !== this) return
        connected = null
        if (InputController.mode(this) == InputController.Mode.ACCESSIBILITY) Engine.stop("无障碍服务已断开")
        LogBus.emit("无障碍服务已断开；不会自动恢复任务")
    }

    override fun onUnbind(intent: Intent?): Boolean { disconnected(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnected(); super.onDestroy() }

    fun foregroundPackage(): String {
        if (connected !== this) return ""
        return try {
            val visible = windows
            try {
                val snapshot = visible.map { window ->
                    val root = window.root
                    val pkg = try { root?.packageName?.toString() } finally { root?.recycle() }
                    ForegroundWindows.Window(window.type, if (Build.VERSION.SDK_INT >= 30) window.displayId else 0,
                        window.isActive, window.isFocused, window.isInPictureInPictureMode, pkg)
                }
                ForegroundWindows.resolve(snapshot, (getSystemService(POWER_SERVICE) as PowerManager).isInteractive,
                    (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked, packageName)
            } finally { visible.forEach { it.recycle() } }
        } catch (_: Exception) { "" }
    }

    /** 每次动作独立保存续接对象；主线程排队超时后，Receipt.start 会拒绝迟到提交。 */
    fun driver(checkSafety: () -> Unit): AccessibleGestureRunner.Driver {
        var stroke: GestureDescription.StrokeDescription? = null
        return AccessibleGestureRunner.Driver { segment, receipt ->
            if (!main.post {
                if (connected !== this) { receipt.reject("无障碍服务已断开"); return@post }
                if (!segment.cleanup) {
                    try { checkSafety() } catch (e: Exception) { receipt.reject(e.message ?: "输入条件已变化"); return@post }
                }
                try {
                    val path = Path().apply {
                        moveTo(segment.fromX.toFloat(), segment.fromY.toFloat())
                        if (segment.x != segment.fromX || segment.y != segment.fromY)
                            lineTo(segment.x.toFloat(), segment.y.toFloat())
                    }
                    val next = if (segment.continuation) {
                        checkNotNull(stroke) { "无可续接的手势" }
                            .continueStroke(path, 0, segment.millis, segment.keepsDown)
                    } else GestureDescription.StrokeDescription(path, 0, segment.millis, segment.keepsDown)
                    val gesture = GestureDescription.Builder().addStroke(next).build()
                    // 提交与过期判断互斥；预检或主线程排队耗时不能导致动作收尾后才迟到注入。
                    synchronized(receipt) {
                        if (!receipt.start()) return@post
                        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) { receipt.complete() }
                            override fun onCancelled(gestureDescription: GestureDescription?) { receipt.cancel() }
                        }, main)
                        if (accepted) stroke = next else receipt.reject()
                    }
                } catch (e: Exception) {
                    LogBus.emit("无障碍手势提交异常：${e.javaClass.simpleName}")
                    receipt.cancel()
                }
            }) receipt.reject()
        }
    }
}
