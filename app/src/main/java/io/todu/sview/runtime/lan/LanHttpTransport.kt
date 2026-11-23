package io.todu.sview.runtime.lan

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 独立于项目网页服务器的 LAN 入口；限制连接、头部及读取时间，来源策略由服务类型决定。 */
internal class LanHttpTransport(address: InetAddress, port: Int, private val route: (Request, Reply) -> Unit,
    private val configuration: Configuration = Configuration(), private val failed: (Exception) -> Unit) : Closeable {
    private val listener = ServerSocket().apply {
        reuseAddress = true
        try { bind(java.net.InetSocketAddress(address, port), configuration.connections) }
        catch (error: Exception) { close(); throw error }
    }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(0, configuration.connections, 20, TimeUnit.SECONDS, SynchronousQueue<Runnable>())
    // 仅自定义服务器设置整次连接期限，防止慢速收发长期占满工作线程。
    private val deadlines = if (configuration.custom) java.util.concurrent.ScheduledThreadPoolExecutor(1).apply {
        removeOnCancelPolicy = true
    } else null
    @Volatile private var closed = false
    val port: Int get() = listener.localPort

    fun start() {
        Thread({
            while (!closed) {
                try {
                    val socket = listener.accept()
                    sockets.add(socket)
                    if (closed) { runCatching { socket.close() }; sockets.remove(socket); break }
                    try { workers.execute { serve(socket) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) {
                        if (configuration.custom) runCatching { Reply(socket.getOutputStream()).bytes(byteArrayOf(), "text/plain", 503) }
                        runCatching { socket.close() }; sockets.remove(socket)
                    }
                } catch (error: Exception) { if (!closed) { failed(error); close() } }
            }
        }, "sview-lan-accept").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(configuration.readTimeout.toLong())
            val expiry = runCatching { deadlines?.schedule({ runCatching { socket.close() } }, configuration.readTimeout.toLong() + 200, TimeUnit.MILLISECONDS) }.getOrNull()
            try {
                socket.soTimeout = configuration.readTimeout
                val input = BufferedInputStream(socket.getInputStream())
                val request = Request.read(input, configuration.custom).also { if (configuration.custom) it.deadlineNanos = deadline }
                val reply = Reply(socket.getOutputStream(), request.method == "HEAD")
                try {
                    // Host 始终校验本机地址；自定义网页可自行通过响应头决定跨来源访问。
                    val host = request.headers["host"] ?: error("缺少 Host")
                    require(host == "${socket.localAddress.hostAddress}:$port") { "访问地址不匹配" }
                    if (!configuration.custom) request.headers["origin"]?.let { require(it == "http://$host") { "不允许跨来源请求" } }
                    if (!configuration.custom) require(request.headers["sec-fetch-site"] != "cross-site") { "不允许跨来源请求" }
                    route(request, reply)
                } catch (error: Exception) {
                    if (!reply.started) reply.json(JSONObject().put("message", error.message ?: "请求失败"), if (error is Unauthorized) 401 else 400)
                }
            } catch (_: Exception) { /* 客户端断开后由会话中的 finally 清理接收文件。 */ }
            finally { expiry?.cancel(false); sockets.remove(socket) }
        }
    }

    override fun close() {
        closed = true
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        deadlines?.shutdownNow()
    }

    /** 自定义服务器支持通用 HTTP 方法；聊天和上传仍保留原有来源限制。 */
    data class Configuration(val connections: Int = 8, val readTimeout: Int = 15000, val custom: Boolean = false)

    class Unauthorized : IllegalStateException("访问码无效或会话已结束")

    class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>,
        val length: Long, val input: InputStream, val target: String = path) {
        var deadlineNanos: Long = Long.MAX_VALUE
        fun text(limit: Int = 8000): String {
            require(length in 0..limit.toLong()) { "请求内容过大" }
            val bytes = ByteArray(length.toInt())
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                check(n > 0) { "请求内容不完整" }; read += n
            }
            return bytes.toString(Charsets.UTF_8)
        }
        companion object {
            fun read(input: InputStream, custom: Boolean = false): Request {
                var total = 0
                fun line(): String {
                    val out = java.io.ByteArrayOutputStream()
                    while (true) {
                        val next = input.read(); check(next >= 0) { "连接已断开" }
                        require(++total <= 16384) { "请求头过大" }
                        if (next == 10) break
                        if (next != 13) out.write(next)
                    }
                    return out.toString("UTF-8")
                }
                val first = line().split(' ')
                require(first.size == 3 && first[0] in (if (custom) listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS") else listOf("GET", "POST")) && first[1].startsWith('/')) { "无效的请求" }
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val value = line(); if (value.isEmpty()) break
                    val key = value.substringBefore(':').lowercase()
                    require(':' in value && key !in headers) { "重复或无效的请求头" }
                    headers[key] = value.substringAfter(':').trim()
                }
                val chunked = custom && headers["transfer-encoding"]?.lowercase() == "chunked"
                require("transfer-encoding" !in headers || chunked) { "不支持此 Transfer-Encoding" }
                require(!chunked || "content-length" !in headers) { "请求正文长度冲突" }
                val length = if (chunked) -1 else if ("content-length" in headers)
                    headers.getValue("content-length").toLongOrNull()?.also { require(it >= 0) { "Content-Length 无效" } }
                        ?: error("Content-Length 无效")
                    else if (custom || first[0] == "GET") 0 else error("缺少 Content-Length")
                val target = first[1].split('?', limit = 2)
                val query = target.getOrNull(1)?.split('&')?.associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                } ?: emptyMap()
                return Request(first[0], target[0], query, headers, length, if (chunked) ChunkedRequestBody(input) else input, first[1])
            }
        }
    }

    class Reply(output: OutputStream, private val head: Boolean = false) {
        private val output = LanTransferIo.output(output)
        var started = false; private set
        private fun headers(type: String, size: Long, status: Int = 200, extra: String = "") {
            started = true
            output.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: $size\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" + extra + "\r\n").toByteArray())
        }
        fun json(value: JSONObject = JSONObject(), status: Int = 200, cookie: String = "") = bytes(value.toString().toByteArray(), "application/json; charset=utf-8", status, cookie)
        fun bytes(value: ByteArray, type: String, status: Int = 200, extra: String = "") {
            headers(type, value.size.toLong(), status, extra); if (!head) output.write(value); output.flush()
        }
        /** 自定义响应头由上层校验；正文按文件分块发送，不载入整个响应。 */
        fun customFile(value: File, status: Int, fields: List<Pair<String, String>>) {
            val noBody = status in listOf(204, 205, 304)
            started = true
            val header = buildString {
                append("HTTP/1.1 $status Result\r\nConnection: close\r\n")
                if (!noBody) append("Content-Length: ${value.length()}\r\n")
                fields.forEach { (name, content) -> append("$name: $content\r\n") }
                append("\r\n")
            }
            output.write(header.toByteArray(Charsets.ISO_8859_1))
            if (!head && !noBody) value.inputStream().use { it.copyTo(output, 32768) }
            output.flush()
        }
        fun file(value: File, type: String, name: String, download: Boolean) {
            val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            headers(type, value.length(), extra = "Content-Disposition: ${if (download) "attachment" else "inline"}; filename*=UTF-8''$encoded\r\n")
            if (!head) value.inputStream().use { it.copyTo(output, 32768) }; output.flush()
        }
    }
}
