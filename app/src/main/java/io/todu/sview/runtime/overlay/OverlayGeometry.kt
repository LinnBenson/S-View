package io.todu.sview.runtime.overlay

import io.todu.sview.data.WindowDimension
import io.todu.sview.data.WindowLocation
import io.todu.sview.data.WindowMargins

/** 在屏幕安全区域中计算窗口，单位为 dp，不依赖悬浮窗口自身的当前尺寸。 */
internal data class OverlayGeometry(val width: Double, val height: Double, val x: Double, val y: Double) {
    companion object {
        fun resolve(screenWidth: Double, screenHeight: Double, density: Double, width: WindowDimension,
            height: WindowDimension, margins: WindowMargins, location: WindowLocation): OverlayGeometry {
            val margin = margins.resolve(screenWidth, screenHeight, density)
            val areaW = (screenWidth - margin.left - margin.right).coerceAtLeast(1.0)
            val areaH = (screenHeight - margin.top - margin.bottom).coerceAtLeast(1.0)
            val w = width.resolveDp(areaW, density).toDouble().coerceAtLeast(1.0)
            val h = height.resolveDp(areaH, density).toDouble().coerceAtLeast(1.0)
            val x = when (location) {
                WindowLocation.TOP_LEFT, WindowLocation.BOTTOM_LEFT -> 0.0
                WindowLocation.TOP_RIGHT, WindowLocation.BOTTOM_RIGHT -> areaW - w
                else -> (areaW - w) / 2
            }
            val y = when (location) {
                WindowLocation.TOP, WindowLocation.TOP_LEFT, WindowLocation.TOP_RIGHT -> 0.0
                WindowLocation.BOTTOM, WindowLocation.BOTTOM_LEFT, WindowLocation.BOTTOM_RIGHT -> areaH - h
                else -> (areaH - h) / 2
            }
            return OverlayGeometry(w, h, margin.left + x, margin.top + y)
        }
    }
}
