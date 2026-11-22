package io.todu.sview.runtime.api

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowInsets
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/** 使用界面上下文所在显示器与窗口信息，同时支持应用窗口和系统悬浮窗口。 */
internal class ScreenInfoReader(private val context: Context) {
    @Suppress("DEPRECATION") // Android 10 及以下通过 Display 与 DecorView 读取同一窗口的尺寸。
    fun read(): JSONObject {
        val activity = findActivity()
        val manager = activity?.windowManager ?: context.getSystemService(android.view.WindowManager::class.java)
        val display = if (Build.VERSION.SDK_INT >= 30) checkNotNull(context.display) { "当前显示器不可用" } else manager.defaultDisplay
        val metrics = context.resources.displayMetrics
        val configuration = context.resources.configuration
        val screenWidth: Int
        val screenHeight: Int
        val windowWidth: Int
        val windowHeight: Int
        val density: Float
        val insets: Insets
        if (Build.VERSION.SDK_INT >= 30) {
            val current = manager.currentWindowMetrics
            val maximum = manager.maximumWindowMetrics
            screenWidth = maximum.bounds.width()
            screenHeight = maximum.bounds.height()
            windowWidth = current.bounds.width()
            windowHeight = current.bounds.height()
            density = if (Build.VERSION.SDK_INT >= 34) current.density else metrics.density
            val native = current.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insets = Insets.of(native.left, native.top, native.right, native.bottom)
        } else {
            val real = DisplayMetrics()
            display.getRealMetrics(real)
            screenWidth = real.widthPixels
            screenHeight = real.heightPixels
            val decor = activity?.window?.decorView
            windowWidth = decor?.width?.takeIf { it > 0 } ?: metrics.widthPixels
            windowHeight = decor?.height?.takeIf { it > 0 } ?: metrics.heightPixels
            density = metrics.density
            insets = decor?.let { ViewCompat.getRootWindowInsets(it) }?.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
        }
        val rotation = when (display.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return ScreenSnapshot.size(screenWidth, screenHeight, density)
            .put("density", density.toDouble()).put("densityDpi", (density * 160).toInt())
            .put("fontScale", configuration.fontScale.toDouble()).put("rotation", rotation)
            .put("orientation", ScreenSnapshot.orientation(screenWidth, screenHeight))
            .put("refreshRate", display.refreshRate.toDouble()).put("displayId", display.displayId)
            .put("isRound", configuration.isScreenRound).put("isInMultiWindowMode", activity?.isInMultiWindowMode == true)
            .put("window", ScreenSnapshot.window(windowWidth, windowHeight, density, insets.left, insets.top, insets.right, insets.bottom))
    }

    private fun findActivity(): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return null
    }
}
