package io.todu.sview.runtime.api

import android.content.Context
import io.todu.sview.data.ProjectRepository
import io.todu.sview.runtime.lan.CustomHttpExchange
import io.todu.sview.runtime.lan.CustomHttpSession
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 控制命令复用系统接口；正文通过已认证的本地二进制通道传输。 */
internal class CustomServerApiBridge(
    context: Context, private val repository: ProjectRepository, private val projectId: String,
    private val event: (JSONObject) -> Unit
) : Closeable {
    private val context = context.applicationContext
    private val sessions = mutableMapOf<String, CustomHttpSession>()
    private val workers = ThreadPoolExecutor(2, 4, 10, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(32)).apply { allowCoreThreadTimeOut(true) }
    private var closed = false

    @Synchronized
    fun invoke(request: JSONObject): JSONObject = try {
        check(!closed) { "运行窗口已关闭" }
        val args = request.getJSONObject("args")
        val id = args.getString("id")
        val result = when (request.getString("method")) {
            "network.server" -> {
                cleanAbandoned(context)
                sessions.entries.removeAll { it.value.isClosed }
                check(sessions.size < 4 && id !in sessions) { "每个项目最多启动四个自定义服务器，标识不能重复" }
                val session = CustomHttpSession(context, repository, projectId, id, args, event)
                try { session.start().also { sessions[id] = session } }
                catch (error: Exception) { session.close(); throw error }
            }
            "network.server.close" -> { sessions.remove(id)?.close(); JSONObject.NULL }
            else -> error("接口不存在")
        }
        JSONObject().put("ok", true).put("value", result)
    } catch (error: Exception) { ApiErrors.failure(error) }

    /** 每块最多 256 KiB，避免正文挤占控制接口和事件连接。 */
    fun dispatch(socket: Socket, input: BufferedInputStream, target: String, length: Int, done: () -> Unit) {
        try {
            workers.execute {
                try {
                    socket.soTimeout = 30000
                    require(length in 0..262144) { "数据块不能超过 256 KB" }
                    val parts = target.removePrefix("/__sview_server/").split('/')
                    require(parts.size == 3) { "请求通道无效" }
                    val session = synchronized(this) { check(!closed); sessions[parts[0]] ?: error("服务器已关闭") }
                    val exchange = session.exchange(parts[1])
                    val bytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(bytes, offset, length - offset)
                        check(count > 0) { "数据块不完整" }; offset += count
                    }
                    when (parts[2]) {
                        "body" -> {
                            val file = exchange.requestFile
                            socket.getOutputStream().apply {
                                write(header(200, "application/octet-stream", file.length()).toByteArray())
                                file.inputStream().use { it.copyTo(this, 32768) }
                            }
                            return@execute
                        }
                        "begin" -> exchange.begin(JSONObject(bytes.toString(Charsets.UTF_8)))
                        "chunk" -> exchange.append(bytes)
                        "end" -> exchange.complete()
                        "fail" -> exchange.fail(CustomHttpExchange.Failure(500, "HANDLER_ERROR", JSONObject(bytes.toString(Charsets.UTF_8)).optString("message", "请求处理失败").take(2000)))
                        else -> error("未知请求操作")
                    }
                    respond(socket, 200, "{}")
                } catch (error: Exception) {
                    runCatching { respond(socket, 400, JSONObject().put("error", error.message ?: "请求已结束").toString()) }
                } finally { done() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            try { respond(socket, 503, "{\"error\":\"请求通道繁忙\"}") } finally { done() }
        }
    }

    private fun header(status: Int, type: String, length: Long) =
        "HTTP/1.1 $status Result\r\nContent-Type: $type\r\nContent-Length: $length\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"

    private fun respond(socket: Socket, status: Int, text: String) {
        val bytes = text.toByteArray()
        socket.getOutputStream().apply { write(header(status, "application/json; charset=utf-8", bytes.size.toLong()).toByteArray()); write(bytes) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        sessions.values.forEach { it.close() }; sessions.clear()
        workers.shutdownNow()
    }

    companion object {
        /** 工作区确认所有窗口已关闭后清理遗留请求文件。 */
        @Synchronized fun clearCaches(context: Context) {
            File(context.cacheDir, "custom-servers").deleteRecursively()
        }
        private var cleaned = false
        @Synchronized private fun cleanAbandoned(context: Context) {
            if (!cleaned) { File(context.cacheDir, "custom-servers").deleteRecursively(); cleaned = true }
        }
    }
}
