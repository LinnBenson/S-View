package io.todu.sview.runtime.api

import android.content.Context
import io.todu.sview.runtime.lan.LanSession
import io.todu.sview.storage.DeviceStorage
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/** 服务属于创建它的运行窗口；关闭旧句柄不会影响同端口的新服务。 */
internal class LocalServerApiBridge(context: Context, private val event: (JSONObject) -> Unit) : Closeable {
    private val context = context.applicationContext
    private val sessions = mutableMapOf<String, LanSession>()
    private var closed = false

    @Synchronized
    fun invoke(request: JSONObject): JSONObject = try {
        check(!closed) { "运行窗口已关闭" }
        val args = request.getJSONObject("args")
        val id = args.getString("id")
        val value = when (request.getString("method")) {
            "network.chatServer", "network.uploadServer" -> {
                cleanAbandoned(context)
                sessions.entries.removeAll { it.value.isClosed }
                check(sessions.size < 4) { "一个项目最多启动四个局域网服务" }
                check(id !in sessions) { "服务标识重复" }
                val session = LanSession(context, id, request.getString("method") == "network.chatServer", args, event)
                try { session.start().also { sessions[id] = session } }
                catch (error: Exception) { session.close(); throw error }
            }
            "network.localServer.send" -> (sessions[id] ?: error("服务已关闭")).send(args.getString("text"))
            "network.localServer.close" -> { sessions.remove(id)?.close(); JSONObject.NULL }
            else -> error("接口不存在")
        }
        JSONObject().put("ok", true).put("value", value)
    } catch (error: Exception) {
        ApiErrors.failure(error)
    }

    @Synchronized
    override fun close() {
        closed = true
        sessions.values.forEach { it.close() }; sessions.clear()
    }

    companion object {
        /** 调用方先确认所有运行窗口已关闭。 */
        @Synchronized fun clearCaches(context: Context) {
            File(context.cacheDir, "lan-sessions").deleteRecursively()
            downloadsCleaned = false
            cleanAbandoned(context)
        }
        private var cacheCleaned = false
        private var downloadsCleaned = false
        /** 进程意外退出时留下的文件只在首次使用时清理，不触碰其他活跃会话。 */
        @Synchronized private fun cleanAbandoned(context: Context) {
            if (!cacheCleaned) { File(context.cacheDir, "lan-sessions").deleteRecursively(); cacheCleaned = true }
            if (!downloadsCleaned && DeviceStorage.allowed(context)) {
                File(DeviceStorage.root(context), "Download").listFiles()?.filter {
                    it.isFile && it.name.matches(Regex("\\.sview-transfer-[a-f0-9-]{36}\\.part"))
                }?.forEach { it.delete() }
                downloadsCleaned = true
            }
        }
    }
}
