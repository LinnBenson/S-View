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

/** 独立于项目网页服务器的 LAN 入口；限制连接、头部及读取时间，不提供跨域访问。 */
internal class LanHttpTransport(address: InetAddress, port: Int, private val route: (Request, Reply) -> Unit,
    private val failed: (Exception) -> Unit) : Closeable {
    private val listener = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(address, port), 8) }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(0, 8, 20, TimeUnit.SECONDS, SynchronousQueue<Runnable>())
    @Volatile private var closed = false
    val port: Int get() = listener.localPort

    fun start() {
        Thread({
            while (!closed) {
                try {
                    val socket = listener.accept()
                    sockets.add(socket)
                    if (closed) { socket.close(); sockets.remove(socket); break }
                    try { workers.execute { serve(socket) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { socket.close(); sockets.remove(socket) }
                } catch (error: Exception) { if (!closed) { failed(error); close() } }
            }
        }, "sview-lan-accept").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 15000
                val input = BufferedInputStream(socket.getInputStream())
                val request = Request.read(input)
                val reply = Reply(socket.getOutputStream())
                try {
                    // 防止恶意网页通过浏览器向局域网服务发请求，以及 DNS 重绑定。
                    val host = request.headers["host"] ?: error("缺少 Host")
                    require(host == "${socket.localAddress.hostAddress}:$port") { "访问地址不匹配" }
                    request.headers["origin"]?.let { require(it == "http://$host") { "不允许跨来源请求" } }
                    require(request.headers["sec-fetch-site"] != "cross-site") { "不允许跨来源请求" }
                    route(request, reply)
                } catch (error: Exception) {
                    if (!reply.started) reply.json(JSONObject().put("message", error.message ?: "请求失败"), if (error is Unauthorized) 401 else 400)
                }
            } catch (_: Exception) { /* 客户端断开后由会话中的 finally 清理接收文件。 */ }
            finally { sockets.remove(socket) }
        }
    }

    override fun close() {
        closed = true
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    class Unauthorized : IllegalStateException("访问码无效或会话已结束")

    class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>,
        val length: Long, val input: InputStream) {
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
            fun read(input: InputStream): Request {
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
                require(first.size == 3 && first[0] in listOf("GET", "POST") && first[1].startsWith('/')) { "无效的请求" }
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val value = line(); if (value.isEmpty()) break
                    val key = value.substringBefore(':').lowercase()
                    require(':' in value && key !in headers) { "重复或无效的请求头" }
                    headers[key] = value.substringAfter(':').trim()
                }
                require("transfer-encoding" !in headers) { "请提供 Content-Length" }
                val length = headers["content-length"]?.toLongOrNull() ?: if (first[0] == "GET") 0 else error("缺少 Content-Length")
                require(length >= 0) { "Content-Length 无效" }
                val target = first[1].split('?', limit = 2)
                val query = target.getOrNull(1)?.split('&')?.associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                } ?: emptyMap()
                return Request(first[0], target[0], query, headers, length, input)
            }
        }
    }

    class Reply(private val output: OutputStream) {
        var started = false; private set
        private fun headers(type: String, size: Long, status: Int = 200, extra: String = "") {
            started = true
            output.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: $size\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" + extra + "\r\n").toByteArray())
        }
        fun json(value: JSONObject = JSONObject(), status: Int = 200, cookie: String = "") = bytes(value.toString().toByteArray(), "application/json; charset=utf-8", status, cookie)
        fun bytes(value: ByteArray, type: String, status: Int = 200, extra: String = "") {
            headers(type, value.size.toLong(), status, extra); output.write(value); output.flush()
        }
        fun file(value: File, type: String, name: String, download: Boolean) {
            val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            headers(type, value.length(), extra = "Content-Disposition: ${if (download) "attachment" else "inline"}; filename*=UTF-8''$encoded\r\n")
            value.inputStream().use { it.copyTo(output, 32768) }; output.flush()
        }
    }
}
