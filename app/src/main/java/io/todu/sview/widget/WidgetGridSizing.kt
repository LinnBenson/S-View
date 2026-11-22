package io.todu.sview.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import kotlin.math.roundToInt

/** 用首次放置的 2×2 尺寸校准桌面格距，避免把固定 dp 当成桌面的一格。 */
internal object WidgetGridSizing {
    private fun preferences(context: Context) = context.getSharedPreferences("widget_grid_sizes", Context.MODE_PRIVATE)

    /** 只在首次配置时记录；重新编辑项目不能把已缩放的尺寸当作 2×2。 */
    fun capture(context: Context, id: Int) {
        val saved = context.getSharedPreferences("project_widgets", Context.MODE_PRIVATE)
        if (saved.contains(id.toString())) return
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val landscapeWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val landscapeHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (minOf(width, height, landscapeWidth, landscapeHeight) <= 0) return
        preferences(context).edit().putString(id.toString(), "$width,$height,$landscapeWidth,$landscapeHeight").apply()
    }

    /** 返回列数和行数；横竖屏分别使用桌面提供的对应尺寸。 */
    fun resolve(context: Context, id: Int, width: Float, height: Float): Pair<Int, Int> {
        val base = preferences(context).getString(id.toString(), null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
        if (base == null || base.size != 4) return cells(width, 90f, 16f) to cells(height, 118f, 16f)
        val padding = AppWidgetHostView.getDefaultPaddingForWidget(context, ComponentName(context, ProjectWidget::class.java), null)
        val density = context.resources.displayMetrics.density
        val horizontalPadding = (padding.left + padding.right) / density
        val verticalPadding = (padding.top + padding.bottom) / density
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).toFloat()
        val landscape = kotlin.math.abs(width - maxWidth) < kotlin.math.abs(width - minWidth)
        val offset = if (landscape) 2 else 0
        return cells(width, (base[offset] + horizontalPadding) / 2f, horizontalPadding) to
            cells(height, (base[offset + 1] + verticalPadding) / 2f, verticalPadding)
    }

    /** 尺寸含桌面预留边距，四舍五入吸收 dp 换算误差。 */
    fun cells(size: Float, pitch: Float, padding: Float): Int =
        ((size + padding) / pitch.coerceAtLeast(1f)).roundToInt().coerceIn(1, 4)

    fun remove(context: Context, id: Int) { preferences(context).edit().remove(id.toString()).apply() }
    fun restore(context: Context, oldId: Int, newId: Int) {
        val prefs = preferences(context)
        prefs.getString(oldId.toString(), null)?.let { prefs.edit().putString(newId.toString(), it).remove(oldId.toString()).apply() }
    }
}
