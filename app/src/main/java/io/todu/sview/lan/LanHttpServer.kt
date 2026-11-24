package io.todu.sview.lan

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 手动启动的 HTTP 服务。密码只用于登录，后续请求使用本次启动生成的会话令牌。 */
class LanHttpServer(
    private val context: Context,
    hosts: List<Inet4Address>,
    port: Int,
    private val password: String,
    private val dispatch: (String, JSONObject) -> LanResponse
) : Closeable {
    private val listeners = mutableListOf<ServerSocket>()
    private val workers = ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val sessions = ConcurrentHashMap<String, Long>()
    private val failures = ArrayDeque<Long>()
    @Volatile private var running = true

    init {
        try {
            hosts.forEach { host ->
                val listener = ServerSocket()
                listeners.add(listener)
                listener.reuseAddress = true
                listener.bind(InetSocketAddress(host, port), 8)
            }
            workers.allowCoreThreadTimeOut(true)
            listeners.forEach { listener ->
                Thread({ accept(listener) }, "局域网编辑器").apply { isDaemon = true; start() }
            }
        } catch (error: Exception) { close(); throw error }
    }

    private fun accept(listener: ServerSocket) {
        while (running) {
            try {
                val socket = listener.accept()
                if (!running) { socket.close(); break }
                sockets.add(socket)
                try { workers.execute { socket.use { serve(it) }; sockets.remove(socket) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { sockets.remove(socket); socket.close() }
            } catch (_: Exception) { if (!running) break }
        }
    }

    /** 限制请求行、头部、正文与超时，不接受分块请求或跨站调用。 */
    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val request = line(input).split(' ')
            require(request.size == 3 && request[2] == "HTTP/1.1") { "请求格式无效" }
            val headers = mutableMapOf<String, String>()
            var ended = false
            for (i in 0 until 40) {
                val text = line(input)
                if (text.isEmpty()) { ended = true; break }
                val colon = text.indexOf(':')
                require(colon > 0)
                val name = text.substring(0, colon).lowercase()
                require(!headers.containsKey(name))
                headers[name] = text.substring(colon + 1).trim()
            }
            require(ended && headers["transfer-encoding"] == null)
            val path = request[1]
            if (request[0] == "GET" && path in ASSETS) {
                val mime = when { path.endsWith(".js") -> "text/javascript"; path.endsWith(".css") -> "text/css"; else -> "text/html" }
                val asset = if (path == "/") "index.html" else path.drop(1)
                respond(socket, LanResponse(bytes = context.assets.open(if (path == "/lucide.js") "templates/lucide-1.77.0.js" else "lan-editor/$asset").use { it.readBytes() }, mime = "$mime; charset=utf-8"))
                return
            }
            require(request[0] == "POST" && path.startsWith("/api/")) { "接口不存在" }
            val host = headers["host"].orEmpty()
            // 只接受直连本机地址，避免 DNS 重绑定借用编辑器的登录状态。
            val localHost = "${socket.localAddress.hostAddress}:${socket.localPort}"
            require(host == localHost && headers["origin"] == "http://$host" && headers["x-sview-editor"] == "1") { "请求来源无效" }
            require(headers["content-type"]?.substringBefore(';') == "application/json")
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            require(length in 2..MAX_BODY) { "单次请求不能超过 24 MB" }
            if (path != "/api/login" && !authorized(headers["authorization"])) {
                respond(socket, LanResponse.error(401, "请重新输入访问密码")); return
            }
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                require(read > 0); offset += read
            }
            val body = JSONObject(bytes.toString(Charsets.UTF_8))
            val result = if (path == "/api/login") login(body) else dispatch(path.removePrefix("/api/"), body)
            respond(socket, result)
        } catch (error: Exception) {
            runCatching { respond(socket, LanResponse.error(400, error.message ?: "请求失败")) }
        }
    }

    private fun authorized(value: String?): Boolean {
        val token = value?.removePrefix("Bearer ") ?: return false
        val expiry = sessions[token] ?: return false
        if (expiry < System.currentTimeMillis()) { sessions.remove(token); return false }
        return true
    }

    @Synchronized private fun login(body: JSONObject): LanResponse {
        val now = System.currentTimeMillis()
        while (failures.isNotEmpty() && failures.first() < now - 60_000) failures.removeFirst()
        if (failures.size >= 10) return LanResponse.error(429, "尝试过多，请一分钟后再试")
        if (!MessageDigest.isEqual(password.toByteArray(), body.optString("password").toByteArray())) {
            failures.addLast(now)
            return LanResponse.error(401, "访问密码不正确")
        }
        sessions.entries.removeIf { it.value < now }
        if (sessions.size >= 16) return LanResponse.error(429, "连接数量已达上限")
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sessions[token] = now + 8 * 60 * 60 * 1000
        return LanResponse.json(JSONObject().put("token", token))
    }

    private fun line(input: BufferedInputStream): String {
        val text = StringBuilder()
        while (true) {
            val c = input.read()
            require(c >= 0 && text.length < 8192)
            if (c == 10) return text.toString().removeSuffix("\r")
            text.append(c.toChar())
        }
    }

    private fun respond(socket: Socket, result: LanResponse) {
        result.use { socket.getOutputStream().apply {
            write(("HTTP/1.1 ${result.status} Response\r\nContent-Type: ${result.mime}\r\nContent-Length: ${result.length}\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n" +
                "Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'\r\n" +
                "Connection: close\r\n\r\n").toByteArray())
            result.writeTo(this)
        } }
    }

    override fun close() {
        running = false
        listeners.forEach { runCatching { it.close() } }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear(); sessions.clear()
        // 先关闭连接；已提交的事务完成回滚/落盘后，响应的 use 负责释放快照。
        // 中断 runBlocking 会丢弃仍在提交的响应，导致文件句柄无人关闭。
        workers.shutdown()
    }

    companion object {
        private const val MAX_BODY = 24 * 1024 * 1024
        private val ASSETS = setOf("/", "/index.html", "/style.css", "/icons.js", "/app.js", "/variables.js", "/icon-gallery.js", "/lucide.js")
    }
}

/** 响应持有独立文件快照；发送成功、断开或失败时均释放句柄和临时文件。 */
class LanResponse(
    val status: Int = 200,
    private val bytes: ByteArray = byteArrayOf(),
    val mime: String = "application/json; charset=utf-8",
    private val input: java.io.InputStream? = null,
    val length: Long = bytes.size.toLong(),
    private val cleanup: () -> Unit = {}
) : Closeable {
    fun writeTo(output: java.io.OutputStream) {
        if (input == null) output.write(bytes) else input.copyTo(output, 65536)
    }
    override fun close() { try { input?.close() } finally { cleanup() } }
    companion object {
        fun json(value: JSONObject) = LanResponse(bytes = value.toString().toByteArray())
        fun error(status: Int, message: String) = LanResponse(status, JSONObject().put("error", message).toString().toByteArray())
        fun file(file: java.io.File, mime: String, cleanup: () -> Unit): LanResponse {
            try { return LanResponse(mime = mime, input = file.inputStream(), length = file.length(), cleanup = cleanup) }
            catch (error: Exception) { cleanup(); throw error }
        }
    }
}
