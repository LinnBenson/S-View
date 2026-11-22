package io.todu.sview.runtime.api

import android.content.Context
import io.todu.sview.data.SharedDatabase
import org.json.JSONObject

/** 在运行窗口的接口工作线程访问共享库，不阻塞主线程或暴露数据库文件。 */
class DatabaseApiBridge(context: Context) {
    private val store by lazy { SharedDatabase.get(context) }

    fun invoke(request: JSONObject): JSONObject = try {
        val args = request.getJSONObject("args")
        fun key(): String = (args.get("key") as? String) ?: error("键必须是字符串")
        val value: Any = when (request.getString("method")) {
            "script.db.get" -> store.get(key())
            "script.db.set" -> { store.set(key(), args.get("value")); JSONObject.NULL }
            "script.db.remove" -> store.remove(key())
            "script.db.getAll" -> store.getAll()
            else -> error("数据库接口不存在")
        }
        JSONObject().put("ok", true).put("value", value)
    } catch (error: Exception) {
        ApiErrors.failure(error)
    }
}
