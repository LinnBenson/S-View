package io.todu.sview.runtime.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ComposeView
import io.todu.sview.data.WindowDimension
import io.todu.sview.data.WindowLocation
import io.todu.sview.data.WindowMargins

internal val LocalRuntimeOverlay = staticCompositionLocalOf<OverlayWindow?> { null }

/** 仅网页矩形占用可触摸窗口；有遮罩时单独添加可点击关闭的底层窗口。 */
internal class OverlayWindow(private val context: Context, private val manager: WindowManager, val view: ComposeView) {
    private var backdrop: View? = null
    private var attached = false
    private var previous: List<Any>? = null
    private var relayout: (() -> Unit)? = null

    fun layout(width: WindowDimension, height: WindowDimension, margins: WindowMargins,
        location: WindowLocation, transparency: Double, close: () -> Unit) {
        relayout = { previous = null; layout(width, height, margins, location, transparency, close) }
        val density = context.resources.displayMetrics.density.toDouble()
        val bounds = if (Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.bounds else
            android.graphics.Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        val insets = if (Build.VERSION.SDK_INT >= 30) manager.maximumWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).let {
                android.graphics.Rect(it.left, it.top, it.right, it.bottom)
            } else android.graphics.Rect()
        val availableW = (bounds.width() - insets.left - insets.right) / density
        val availableH = (bounds.height() - insets.top - insets.bottom) / density
        val frame = OverlayGeometry.resolve(availableW, availableH, density, width, height, margins, location)
        val next = listOf(frame, transparency, bounds.width(), bounds.height(), density, insets.toString())
        backdrop?.setOnClickListener { close() }
        if (previous == next) return
        previous = next
        if (transparency < 100 && backdrop == null) {
            addBackdrop(close)
        } else if (transparency == 100.0) {
            backdrop?.let { manager.removeViewImmediate(it) }; backdrop = null
        }
        backdrop?.setBackgroundColor(android.graphics.Color.argb(((100 - transparency) * 2.55).toInt(), 0, 0, 0))
        val layout = params((frame.width * density).toInt().coerceAtLeast(1), (frame.height * density).toInt().coerceAtLeast(1)).apply {
            this.x = insets.left + (frame.x * density).toInt()
            this.y = insets.top + (frame.y * density).toInt()
        }
        if (attached) manager.updateViewLayout(view, layout) else { manager.addView(view, layout); attached = true }
    }

    /** 初始窗口先附着以启动 Compose，首轮布局再应用项目尺寸。 */
    fun attach(transparency: Double, close: () -> Unit) {
        if (transparency < 100) addBackdrop(close)
        manager.addView(view, params(1, 1)); attached = true
    }

    private fun addBackdrop(close: () -> Unit) {
        check(!attached) { "遮罩需要先于运行窗口创建" }
        backdrop = View(context).also { background ->
            background.setOnClickListener { close() }
            manager.addView(background, params(-1, -1).apply { flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE })
        }
    }
    fun configurationChanged() { relayout?.invoke() }
    fun close() {
        relayout = null
        if (attached) { runCatching { manager.removeViewImmediate(view) }; attached = false }
        backdrop?.let { runCatching { manager.removeViewImmediate(it) } }; backdrop = null
        view.disposeComposition()
    }

    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
}
