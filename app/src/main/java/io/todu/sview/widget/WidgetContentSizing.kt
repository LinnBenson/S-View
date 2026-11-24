package io.todu.sview.widget

import android.content.Context
import android.util.TypedValue
import android.widget.RemoteViews
import io.todu.sview.R
import kotlin.math.min
import kotlin.math.roundToInt

/** 根据每格的可用空间调整内容，不改变桌面占格数或项目顺序。 */
internal object WidgetContentSizing {
    data class Content(val icon: Float, val text: Float, val gap: Float)

    /** 扣除容器边距、色块外距与内距后缩放，宽高同时约束，避免扁长格子撑大内容。 */
    fun calculate(layout: WidgetGridSizing.Layout): Content {
        val width = ((layout.width - 16f) / layout.columns - 16f).coerceAtLeast(1f)
        val height = ((layout.height - 16f) / layout.rows - 16f).coerceAtLeast(1f)
        val scale = min(width / 64f, height / 72f).coerceIn(.8f, 1.6f)
        return Content(27f * scale, 11f * scale.coerceAtMost(1.23f), 8f * scale)
    }

    /** 图标使用布局约束缩放矢量；文字仍使用 sp，保留系统字体大小设置。 */
    fun apply(context: Context, views: RemoteViews, content: Content) {
        val density = context.resources.displayMetrics.density
        val icon = (content.icon * density).roundToInt()
        views.setInt(R.id.widget_icon, "setMinimumWidth", icon)
        views.setInt(R.id.widget_icon, "setMinimumHeight", icon)
        views.setTextViewTextSize(R.id.widget_name, TypedValue.COMPLEX_UNIT_SP, content.text)
        views.setViewPadding(R.id.widget_name, 0, (content.gap * density).roundToInt(), 0, 0)
    }
}
