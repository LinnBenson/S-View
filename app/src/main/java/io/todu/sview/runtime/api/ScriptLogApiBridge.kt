package io.todu.sview.runtime.api

import android.app.Application
import android.content.Context
import io.todu.sview.data.WorkspaceOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 接口工作线程等待日志结果；工作区状态在主线程访问，文件仍由工作区 IO 队列读取。 */
internal class ScriptLogApiBridge(context: Context, private val projectId: String) {
    private val application = context.applicationContext as Application

    fun invoke(request: JSONObject): JSONObject = try {
        val args = request.getJSONObject("args")
        val name = if (args.has("name")) {
            require(args.get("name") is String) { "项目名称应为非空字符串" }
            args.getString("name").also { require(it.isNotBlank()) { "项目名称不能为空白" } }
        } else null
        val text = runBlocking {
            withContext(Dispatchers.Main.immediate) {
                WorkspaceOwner.model(application).readProjectLog(projectId, name)
            }
        }
        JSONObject().put("ok", true).put("value", text)
    } catch (error: Exception) {
        ApiErrors.failure(error)
    }
}
