package io.todu.sview.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** 校准只使用用户确认的占格数；实际尺寸优先取桌面报告的 SizeF，不假定首次一定为 2×2。 */
internal object WidgetGridSizing {
    data class Layout(val width: Float, val height: Float, val columns: Int, val rows: Int)
    private data class Size(val key: String, val width: Float, val height: Float)
    private fun preferences(context: Context) = context.getSharedPreferences("widget_grid_sizes", Context.MODE_PRIVATE)

    /** 同一实例的横竖屏和折叠状态分开保存，配置 Activity 的窗口尺寸不参与分组。 */
    private fun profile(context: Context): String {
        val config = context.applicationContext.resources.configuration
        return "${config.screenWidthDp}:${config.screenHeightDp}:${config.densityDpi}"
    }

    private fun read(context: Context, id: Int): JSONObject = runCatching {
        JSONObject(preferences(context).getString(id.toString(), "{}")!!)
    }.getOrDefault(JSONObject())

    /** Android 12 以上使用真实候选尺寸；旧桌面只提供范围时分别保留横竖屏候选。 */
    @Suppress("DEPRECATION")
    private fun sizes(options: Bundle): List<Size> {
        val exact = if (Build.VERSION.SDK_INT >= 31) options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            ?.filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
            ?.distinct()?.sortedWith(compareBy<SizeF> { it.width }.thenBy { it.height })?.take(16) else null
        if (!exact.isNullOrEmpty()) return exact.mapIndexed { index, value -> Size("exact:${exact.size}:$index", value.width, value.height) }
        return listOf(
            Size("landscape", options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).toFloat(), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat()),
            Size("portrait", options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat(), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat())
        ).filter { it.width > 0 && it.height > 0 }
    }

    private fun samples(value: JSONArray?): List<WidgetGridAxis.Sample> = if (value == null) emptyList() else
        (0 until value.length()).mapNotNull { index ->
            val item = value.optJSONObject(index) ?: return@mapNotNull null
            val size = item.optDouble("size", 0.0).toFloat()
            val span = item.optInt("span")
            if (size.isFinite() && size > 0 && span in 1..4) WidgetGridAxis.Sample(size, span) else null
        }

    /** 一次读取配置，为整个尺寸映射生成布局，避免每个候选尺寸重复查询桌面。 */
    @Synchronized
    fun layouts(context: Context, id: Int, options: Bundle): List<Layout> {
        val dimensions = sizes(options)
        val state = read(context, id)
        val pending = state.optJSONObject("pending")
        if (pending != null && dimensions.isNotEmpty() && pending.optString("profile") == profile(context)) {
            storeCalibration(context, id, state, dimensions, pending.getInt("columns"), pending.getInt("rows"))
        }
        val variants = state.optJSONObject(profile(context))
        val padding = AppWidgetHostView.getDefaultPaddingForWidget(context, ComponentName(context, ProjectWidget::class.java), null)
        val density = context.resources.displayMetrics.density
        return dimensions.map { size ->
            val saved = variants?.optJSONObject(size.key)
            Layout(size.width, size.height,
                WidgetGridAxis.resolve(size.width, samples(saved?.optJSONArray("x")), 90f, (padding.left + padding.right) / density),
                WidgetGridAxis.resolve(size.height, samples(saved?.optJSONArray("y")), 118f, (padding.top + padding.bottom) / density))
        }
    }

    /** 新实例以声明的默认占格数预填，已有实例使用当前估算，保存前由用户确认。 */
    fun current(context: Context, id: Int): Pair<Int, Int> {
        if (!context.getSharedPreferences("project_widgets", Context.MODE_PRIVATE).contains(id.toString())) return 2 to 2
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val landscape = context.applicationContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return layouts(context, id, options).minByOrNull { abs(it.width - width) + abs(it.height - height) }
            ?.let { it.columns to it.rows } ?: (2 to 2)
    }

    /** 无尺寸的首次配置只保存已确认的占格数，等桌面首次报告尺寸后再建立基准。 */
    @Synchronized
    fun calibrate(context: Context, id: Int, columns: Int, rows: Int) {
        require(columns in 1..4 && rows in 1..4)
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val state = read(context, id)
        val dimensions = sizes(options)
        if (dimensions.isEmpty()) {
            state.put("pending", JSONObject().put("profile", profile(context)).put("columns", columns).put("rows", rows))
            preferences(context).edit().putString(id.toString(), state.toString()).apply()
        } else storeCalibration(context, id, state, dimensions, columns, rows)
    }

    private fun storeCalibration(context: Context, id: Int, state: JSONObject, dimensions: List<Size>, columns: Int, rows: Int) {
        val key = profile(context)
        val variants = state.optJSONObject(key) ?: JSONObject().also { state.put(key, it) }
        dimensions.forEach { size ->
            val saved = variants.optJSONObject(size.key) ?: JSONObject().also { variants.put(size.key, it) }
            fun update(axis: String, sizeDp: Float, span: Int) {
                val updated = WidgetGridAxis.calibrate(samples(saved.optJSONArray(axis)), sizeDp, span)
                saved.put(axis, JSONArray().apply { updated.forEach { put(JSONObject().put("size", it.size).put("span", it.span)) } })
            }
            update("x", size.width, columns)
            update("y", size.height, rows)
        }
        state.remove("pending")
        preferences(context).edit().putString(id.toString(), state.toString()).apply()
    }

    fun remove(context: Context, id: Int) { preferences(context).edit().remove(id.toString()).apply() }
    fun restore(context: Context, oldId: Int, newId: Int) {
        // 桌面恢复可能跨设备或网格配置，项目顺序保留，尺寸校准重新确认。
        preferences(context).edit().remove(oldId.toString()).remove(newId.toString()).apply()
    }
}
