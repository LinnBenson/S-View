package io.todu.sview.runtime.network

import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.Socket
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.io.Closeable

/** 本地二进制桥，控制消息和数据块分开；认证由 ProjectServer 在转交前完成。 */
class NetworkRoutes(private val network: NativeNetwork) : Closeable {
    private val workers = ThreadPoolExecutor(0, 12, 10, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue())

    fun dispatch(socket: Socket, input: BufferedInputStream, target: String, length: Int, done: () -> Unit) {
        val parts = target.removePrefix("/__sview_network/").split('/')
        val id = parts.first()
        val operation = parts.getOrNull(1) ?: id
        // 取消不能排在长时间等待的上传、下载后面。
        if (operation == "cancel") {
            try { network.cancel(id); json(socket, JSONObject()) } finally { done() }
            return
        }
        try {
            workers.execute {
                try {
                    socket.soTimeout = 30000
                    require(length in 0..262144) { "数据块不能超过 256 KB" }
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(body, offset, length - offset)
                        require(count > 0) { "请求体不完整" }
                        offset += count
                    }
                    when (operation) {
                        "create" -> json(socket, network.create(JSONObject(body.toString(Charsets.UTF_8))))
                        "meta" -> json(socket, network.metadata(id))
                        "upload" -> json(socket, network.upload(id, body, false))
                        "end" -> json(socket, network.upload(id, body, true))
                        "read" -> network.read(id) { bytes, loaded, total, end, file ->
                            respond(socket, 200, "application/octet-stream", bytes,
                                "X-SView-Loaded: $loaded\r\nX-SView-Total: $total\r\nX-SView-End: $end\r\nX-SView-File: $file\r\n")
                        }
                        "file" -> {
                            val file = network.file(JSONObject(body.toString(Charsets.UTF_8)).getString("path"))
                            val output = socket.getOutputStream()
                            output.write(header(200, "application/octet-stream", file.length(), "").toByteArray())
                            file.inputStream().use { it.copyTo(output) }
                        }
                        else -> error("未知网络操作")
                    }
                } catch (error: Exception) {
                    val message = error.cause?.message ?: error.message ?: "网络请求失败"
                    val code = network.errorCode(id)
                    network.cancel(id)
                    runCatching { respond(socket, 400, "application/json; charset=utf-8",
                        JSONObject().put("error", message).put("code", code).toString().toByteArray()) }
                } finally { done() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            try { respond(socket, 503, "application/json", "{\"error\":\"网络通道繁忙\",\"code\":\"BUSY\"}".toByteArray()) }
            finally { done() }
        }
    }

    private fun json(socket: Socket, data: JSONObject) = respond(socket, 200, "application/json; charset=utf-8", data.toString().toByteArray())
    private fun header(status: Int, mime: String, size: Long, extra: String) =
        "HTTP/1.1 $status Result\r\nContent-Type: $mime\r\nContent-Length: $size\r\n${extra}Cache-Control: no-store\r\nConnection: close\r\n\r\n"
    private fun respond(socket: Socket, status: Int, mime: String, bytes: ByteArray, extra: String = "") {
        socket.getOutputStream().apply { write(header(status, mime, bytes.size.toLong(), extra).toByteArray()); write(bytes) }
    }
    override fun close() {
        try { network.close() } finally { workers.shutdownNow() }
    }
}
