package io.todu.sview.wear

import android.content.Context
import io.todu.sview.data.Project
import io.todu.sview.data.ProjectJson
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** 磁贴直接读取已发布的工作区，不初始化编辑器或脚本内核。 */
internal object WatchTileStore {
    const val MAX_PROJECTS = 8
    const val FILE_NAME = "tile-projects.json"

    fun projects(context: Context): List<Project> = synchronized(WatchWorkspaceStore) {
        if (!WatchDevice.isWatch(context)) return emptyList()
        val root = WatchWorkspaceStore.root(context)
        val selection = File(root, FILE_NAME)
        if (!selection.isFile) return emptyList()
        val ids = JSONArray(selection.readText()).let { array ->
            List(array.length().coerceAtMost(MAX_PROJECTS)) { array.getString(it) }.toSet()
        }
        val data = JSONArray(File(root, "projects.json").readText())
        // 以项目顺序为准，与手机拖动排序保持一致。
        List(data.length()) { data.getJSONObject(it) }.filter { it.getString("id") in ids }.map(ProjectJson::decode)
    }

    /** 启动只检查发布列表，完整项目配置交给工作区加载。 */
    fun allowsLaunch(context: Context, id: String): Boolean = synchronized(WatchWorkspaceStore) {
        val file = File(WatchWorkspaceStore.root(context), FILE_NAME)
        if (!file.isFile) return false
        val ids = JSONArray(file.readText())
        (0 until minOf(ids.length(), MAX_PROJECTS)).any { ids.getString(it) == id }
    }

    /** 公开的磁贴启动入口只接受本应用签发的令牌，不能由任意外部 Intent 运行项目。 */
    @Synchronized fun token(context: Context): String {
        val preferences = context.getSharedPreferences("watch-tile", Context.MODE_PRIVATE)
        return preferences.getString("token", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("token", it).commit())
        }
    }
}
