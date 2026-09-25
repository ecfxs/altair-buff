package com.altair.probe

import android.content.Context
import android.graphics.Point
import android.view.WindowManager

/** 标记与注入使用同一默认显示屏坐标系，不使用截图或猜测分辨率。 */
data class ScreenGeometry(val width: Int, val height: Int, val rotation: Int) {
    val key: String get() = "$width,$height,$rotation"
    fun x(n: Float) = (n * width).toInt().coerceIn(0, width - 1)
    fun y(n: Float) = (n * height).toInt().coerceIn(0, height - 1)
    companion object {
        @Suppress("DEPRECATION")
        fun read(ctx: Context): ScreenGeometry {
            val display = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            val size = Point()
            display.getRealSize(size)
            check(display.displayId == 0 && size.x > 0 && size.y > 0) { "无法确定默认显示屏尺寸" }
            return ScreenGeometry(size.x, size.y, display.rotation)
        }
    }
}
