package io.todu.sview.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import android.view.View
import androidx.compose.ui.graphics.toArgb
import io.todu.sview.R
import io.todu.sview.ui.components.CardColors
import io.todu.sview.ui.components.ProjectIconCatalog
import io.todu.sview.data.Project
import io.todu.sview.data.ProjectJson
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File

/** 每个小部件保存独立的槽位顺序，缩小时隐藏的项目不会丢失。 */
internal object WidgetStore {
    const val SLOTS = 16
    private fun preferences(context: Context) = context.getSharedPreferences("project_widgets", Context.MODE_PRIVATE)
    fun read(context: Context, id: Int): List<String> {
        val array = runCatching { JSONArray(preferences(context).getString(id.toString(), "[]")) }.getOrDefault(JSONArray())
        return List(SLOTS) { array.optString(it, "") }
    }
    fun save(context: Context, id: Int, projects: List<String>) {
        preferences(context).edit().putString(id.toString(), JSONArray(projects.take(SLOTS)).toString()).apply()
    }
    fun remove(context: Context, id: Int) { preferences(context).edit().remove(id.toString()).apply() }
    fun projects(context: Context): List<Project> {
        val file = File(context.filesDir, "projects.json")
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return List(array.length()) { ProjectJson.decode(array.getJSONObject(it)) }
    }
}

/** 使用原生 RemoteViews 网格，由桌面负责拖动与缩放；无需周期唤醒。 */
class ProjectWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAsync(context, ids)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = updateAsync(context, intArrayOf(id))
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { WidgetStore.remove(context, it); WidgetGridSizing.remove(context, it) } }
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        oldIds.zip(newIds).forEach { (old, new) -> WidgetStore.save(context, new, WidgetStore.read(context, old)); WidgetStore.remove(context, old); WidgetGridSizing.restore(context, old, new) }
        updateAsync(context, newIds)
    }

    private fun updateAsync(context: Context, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { update(context, ids) } catch (error: Exception) { android.util.Log.e("ProjectWidget", "更新小部件失败", error) }
            finally { pending.finish() }
        }
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            update(context, manager.getAppWidgetIds(ComponentName(context, ProjectWidget::class.java)))
        }

        fun update(context: Context, ids: IntArray) {
            if (ids.isEmpty()) return
            val projects = WidgetStore.projects(context).associateBy { it.id }
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                val selected = WidgetStore.read(context, id)
                val layouts = WidgetGridSizing.layouts(context, id, options)
                fun render(layout: WidgetGridSizing.Layout): RemoteViews =
                    grid(context, id, selected, projects, layout)
                val sizes = if (Build.VERSION.SDK_INT >= 31) options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES) else null
                val views = when {
                    layouts.isEmpty() -> grid(context, id, selected, projects, WidgetGridSizing.Layout(106f, 134f, 1, 1))
                    Build.VERSION.SDK_INT >= 31 && !sizes.isNullOrEmpty() ->
                        RemoteViews(layouts.associate { SizeF(it.width, it.height) to render(it) })
                    else -> RemoteViews(render(layouts.first()), render(layouts.last()))
                }
                manager.updateAppWidget(id, views)
            }
        }

        private fun grid(context: Context, widgetId: Int, selected: List<String>, projects: Map<String, Project>, layout: WidgetGridSizing.Layout): RemoteViews {
            val columns = layout.columns
            val rows = layout.rows
            val content = WidgetContentSizing.calculate(layout)
            val result = RemoteViews(context.packageName, R.layout.widget_projects)
            result.removeAllViews(R.id.widget_grid)
            repeat(rows) { rowIndex ->
                val row = RemoteViews(context.packageName, R.layout.widget_row)
                repeat(columns) tileLoop@ { column ->
                    val slot = rowIndex * columns + column
                    val project = projects[selected[slot]]
                    val tile = RemoteViews(context.packageName, R.layout.widget_tile)
                    if (project == null) {
                        // 留住网格位置，但不显示色块、文字，也不设置点击入口。
                        tile.setViewVisibility(R.id.widget_tile, View.INVISIBLE)
                        row.addView(R.id.widget_row, tile)
                        return@tileLoop
                    }
                    tile.setViewVisibility(R.id.widget_tile, View.VISIBLE)
                    tile.setTextViewText(R.id.widget_name, project.name)
                    WidgetContentSizing.apply(context, tile, content)
                    // 复用项目图标目录的资源引用，避免发布包资源名称压缩后查找失效。
                    tile.setImageViewResource(R.id.widget_icon, ProjectIconCatalog.byName[project.icon]?.resource ?: R.drawable.lucide_code)
                    tile.setInt(R.id.widget_tile_color, "setColorFilter", CardColors[project.color.coerceIn(CardColors.indices)].toArgb())
                    tile.setContentDescription(R.id.widget_tile, "运行 ${project.name}")
                    val intent = Intent(context, WidgetLaunchActivity::class.java).putExtra("projectId", project.id)
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        .setData(Uri.parse("sview-widget://$widgetId/$slot"))
                    tile.setOnClickPendingIntent(R.id.widget_tile, PendingIntent.getActivity(context, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    row.addView(R.id.widget_row, tile)
                }
                result.addView(R.id.widget_grid, row)
            }
            return result
        }
    }
}
