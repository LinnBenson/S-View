package io.todu.sview.runtime.storage

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 文件控制与正文使用独立通道，认证和来源校验由 ProjectServer 统一完成。 */
internal class StorageRoutes(private val storage: ScriptStorage) : Closeable {
    private val workers = ThreadPoolExecutor(0, 2, 10, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16))

    fun dispatch(socket: Socket, input: BufferedInputStream, target: String, length: Int, done: () -> Unit) {
        try {
            workers.execute {
                try {
                    socket.soTimeout = 30000
                    require(length in 0..ScriptStorage.CHUNK_SIZE) { "数据块不能超过 256 KB" }
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(body, offset, length - offset)
                        require(count > 0) { "请求体不完整" }
                        offset += count
                    }
                    val parts = target.removePrefix("/__sview_storage/").split('/')
                    when (parts[0]) {
                        "read" -> respond(socket, 200, "application/octet-stream", storage.read(parts.getOrElse(1) { "" }))
                        "write" -> {
                            storage.write(parts.getOrElse(1) { "" }, body)
                            json(socket, JSONObject())
                        }
                        else -> json(socket, JSONObject().put("value", storage.command(parts[0], JSONObject(body.toString(Charsets.UTF_8)))))
                    }
                } catch (error: Exception) {
                    runCatching { respond(socket, 400, "application/json; charset=utf-8", io.todu.sview.runtime.api.ApiErrors.failure(error).toString().toByteArray()) }
                } finally { done() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            try { respond(socket, 503, "application/json", "{\"error\":\"文件操作繁忙\",\"code\":\"BUSY\"}".toByteArray()) }
            finally { done() }
        }
    }

    private fun json(socket: Socket, result: JSONObject) = respond(socket, 200, "application/json; charset=utf-8", result.toString().toByteArray())

    private fun respond(socket: Socket, status: Int, mime: String, bytes: ByteArray) {
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status Result\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
        }
    }

    override fun close() {
        try { storage.close() } finally { workers.shutdownNow() }
    }
}
