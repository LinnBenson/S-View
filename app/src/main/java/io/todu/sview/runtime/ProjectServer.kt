package io.todu.sview.runtime

import io.todu.sview.runtime.network.NativeNetwork
import io.todu.sview.runtime.network.NetworkRoutes
import io.todu.sview.data.Project
import io.todu.sview.data.ProjectRepository
import io.todu.sview.data.RuntimeDocument
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 仅监听回环地址。阻塞等待请求，不轮询；运行窗口关闭即回收线程与套接字。 */
class ProjectServer(private val repository: ProjectRepository, private val project: Project, private val onLog: (String) -> Unit = {},
    private val onApi: (JSONObject) -> JSONObject,
    private val onApiSent: (JSONObject, JSONObject) -> Unit = { _, _ -> },
    port: Int = 0, private val share: io.todu.sview.share.SharedPayload? = null) : Closeable {
    private val logLock = Any()
    private val token = UUID.randomUUID().toString()
    private val server = ServerSocket().apply {
        reuseAddress = true
        try { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8) }
        catch (error: Exception) { close(); throw error }
    }
    val port: Int get() = server.localPort
    private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(32)).apply { allowCoreThreadTimeOut(true) }
    // 等待硬件的调用与快速查询分开；关闭操作不排在定位、扫描或健康读取后面。
    private val apiWorkers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16)).apply { allowCoreThreadTimeOut(true) }
    private val slowApiWorkers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16)).apply { allowCoreThreadTimeOut(true) }
    private val controlWorkers = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16)).apply { allowCoreThreadTimeOut(true) }
    private val running = AtomicBoolean(true)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val storage = lazy { io.todu.sview.runtime.storage.StorageRoutes(io.todu.sview.runtime.storage.ScriptStorage(
        repository.root(project.id), repository::deviceStorageRoot, repository.temporaryFiles, repository.transferChunkSize
    ) { path, action -> repository.changeSource(project.id, path, action) }) }
    private val network = lazy { NetworkRoutes(NativeNetwork(repository, project.id)) }
    val events = WindowEvents { sockets.remove(it) }
    val uri: String = "http://127.0.0.1:${server.localPort}/index.html?access=$token"

    init {
        Thread({
            while (running.get()) {
                try {
                    val socket = server.accept()
                    sockets.add(socket)
                    try { workers.execute { if (!serve(socket)) { socket.close(); sockets.remove(socket) } } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { sockets.remove(socket); socket.close() }
                } catch (_: Exception) { if (!running.get()) break }
            }
        }, "项目资源监听").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket): Boolean {
        try {
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            val request = readLine(input).split(' ')
            if (request.size < 2) return false
            val headers = mutableMapOf<String, String>()
            for (index in 0 until 50) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
            val target = request[1]
            val authorized = target.substringAfter('?', "").split('&').contains("access=$token") ||
                headers["cookie"]?.split(';')?.any { it.trim() == "sview=$token" } == true
            if (!authorized) { respond(socket, 403, "text/plain", "禁止访问".toByteArray()); return false }
            if (target == "/__sview_share" || target.startsWith("/__sview_share/")) {
                io.todu.sview.share.ShareResources.serve(socket, target, request[0], headers, share)
                return false
            }
            if (target.substringBefore('?') == "/__sview_device") {
                DeviceImageResource.serve(repository, project.id, socket, target, request[0], headers)
                return false
            }
            if (target.startsWith("/__sview_network/") || target.startsWith("/__sview_storage/")) {
                if (request[0] != "POST" || headers["origin"] != "http://127.0.0.1:${server.localPort}" || headers["x-sview-request"] != "1") {
                    respond(socket, 403, "text/plain", byteArrayOf()); return false
                }
                synchronized(logLock) {
                    if (!running.get()) return false
                    val done = { runCatching { socket.close() }; sockets.remove(socket); Unit }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    if (target.startsWith("/__sview_storage/")) storage.value.dispatch(socket, input, target, length, done)
                    else network.value.dispatch(socket, input, target, length, done)
                }
                return true
            }
            if (target.substringBefore('?') == "/__sview_events") {
                val origin = "http://127.0.0.1:${server.localPort}"
                if (request[0] != "GET" || (headers["origin"] != null && headers["origin"] != origin) || headers["sec-fetch-site"] == "cross-site") {
                    respond(socket, 403, "text/plain", byteArrayOf()); return false
                }
                val after = target.substringAfter('?', "").split('&').firstOrNull { it.startsWith("after=") }?.substringAfter('=')?.toLongOrNull()
                events.attach(socket, after ?: headers["last-event-id"]?.toLongOrNull() ?: 0)
                return true
            }
            if (target == "/__sview_events_ack") {
                if (request[0] != "POST" || headers["origin"] != "http://127.0.0.1:${server.localPort}" || headers["x-sview-request"] != "1") {
                    respond(socket, 403, "text/plain", byteArrayOf()); return false
                }
                events.acknowledgeClose()
                respond(socket, 200, "text/plain", byteArrayOf()); return false
            }
            if (target == "/__sview_api") {
                // 自定义头和精确 Origin 校验阻止外部页面借用会话 Cookie 调用系统能力。
                val origin = "http://127.0.0.1:${server.localPort}"
                if (request[0] != "POST" || headers["origin"] != origin || headers["x-sview-request"] != "1" ||
                    headers["content-type"]?.substringBefore(';')?.trim() != "application/json") {
                    respond(socket, 403, "text/plain", byteArrayOf()); return false
                }
                val length = headers["content-length"]?.toIntOrNull()
                if (length == null || length !in 1..65536) {
                    respond(socket, 413, "text/plain", byteArrayOf()); return false
                }
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(bytes, offset, length - offset)
                    require(count > 0)
                    offset += count
                }
                val data = JSONObject(bytes.toString(Charsets.UTF_8))
                try {
                    apiExecutor(data.optString("method")).execute {
                        try {
                            val result = onApi(data)
                            respond(socket, 200, "application/json; charset=utf-8", result.toString().toByteArray())
                            onApiSent(data, result)
                        } catch (_: Exception) {
                            runCatching { respond(socket, 500, "text/plain", "接口调用失败".toByteArray()) }
                        } finally { runCatching { socket.close() }; sockets.remove(socket) }
                    }
                    return true
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    respond(socket, 503, "text/plain", "接口请求过多，请稍后重试".toByteArray())
                    return false
                }
            }
            if (request[0] == "POST" && target == "/__sview_console") {
                if (headers["origin"] != "http://127.0.0.1:${server.localPort}") {
                    respond(socket, 403, "text/plain", byteArrayOf()); return false
                }
                val length = headers["content-length"]?.toIntOrNull()
                if (length == null || length !in 0..65536) {
                    respond(socket, 413, "text/plain", byteArrayOf()); return false
                }
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) { val count = input.read(bytes, read, length - read); if (count < 0) break; read += count }
                synchronized(logLock) {
                    if (running.get()) onLog(bytes.copyOf(read).toString(Charsets.UTF_8).take(16384))
                }
                respond(socket, 200, "text/plain", byteArrayOf()); return false
            }
            if (request[0] != "GET" && request[0] != "HEAD") { respond(socket, 405, "text/plain", byteArrayOf()); return false }
            if (target.substringBefore('?') == "/__sview_network.js") {
                respond(socket, 200, "text/javascript; charset=utf-8", repository.networkScript(), request[0] == "HEAD")
                return false
            }
            if (target.substringBefore('?') == "/__sview_storage.js") {
                respond(socket, 200, "text/javascript; charset=utf-8", repository.storageScript(), request[0] == "HEAD")
                return false
            }
            val path = URLDecoder.decode(target.substringBefore('?'), "UTF-8")
            val file = repository.runtimeFile(project.id, path)
            if (!file.isFile) { respond(socket, 404, "text/plain", "文件不存在".toByteArray()); return false }
            val mime = when (file.extension.lowercase()) {
                "js", "mjs" -> "text/javascript; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "html", "htm" -> "text/html; charset=utf-8"
                "svg" -> "image/svg+xml"
                "json" -> "application/json; charset=utf-8"
                else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            }
            // 入口和主脚本直接读取保存时生成的文件，不在请求路径拼装内容。
            if (mime.startsWith("text/html") && path.removePrefix("/") != "index.html" && file.length() <= 4 * 1024 * 1024) {
                val content = RuntimeDocument.render(project, file.readText(), entry = false)
                respond(socket, 200, mime, content.toByteArray(), request[0] == "HEAD")
            } else {
                // 保持重新校验语义；文件未变化时无需再次传输脚本或库内容。
                val etag = "W/\"${file.lastModified()}-${file.length()}\""
                val extra = "ETag: $etag\r\n"
                if (headers["if-none-match"] == etag) {
                    socket.getOutputStream().write(header(304, mime, 0, extra).toByteArray())
                    return false
                }
                val output = socket.getOutputStream()
                output.write(header(200, mime, file.length(), extra).toByteArray())
                if (request[0] != "HEAD") file.inputStream().use { it.copyTo(output) }
            }
        } catch (_: Exception) { runCatching { respond(socket, 400, "text/plain", "无法读取资源".toByteArray()) } }
        return false
    }

    private fun readLine(input: BufferedInputStream): String {
        val buffer = StringBuilder()
        while (buffer.length < 8192) {
            val value = input.read()
            if (value < 0 || value == 10) break
            if (value != 13) buffer.append(value.toChar())
        }
        return buffer.toString()
    }

    private fun header(status: Int, mime: String, size: Long, extra: String = "") =
        "HTTP/1.1 $status ${when (status) { 200 -> "OK"; 304 -> "Not Modified"; else -> "Error" }}\r\nContent-Type: $mime\r\nContent-Length: $size\r\n${if (status == 200 || status == 304) "Set-Cookie: sview=$token; HttpOnly; SameSite=Strict; Path=/\r\n" else ""}${extra}Cache-Control: no-cache\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"

    private fun respond(socket: Socket, status: Int, mime: String, body: ByteArray, head: Boolean = false) {
        socket.getOutputStream().apply { write(header(status, mime, body.size.toLong()).toByteArray()); if (!head) write(body) }
    }


    private fun apiExecutor(method: String): ThreadPoolExecutor = when (method) {
        "interaction.close", "interaction.closeThis", "network.hotspot.close", "network.localServer.close",
        "system.nfc.close", "system.cancelVibration" -> controlWorkers
        "system.getLocation", "system.scanWifi", "system.scanBluetooth", "system.getHealthInfo", "system.install",
        "network.hotspot", "interaction.preview", "resource.openDevice" -> slowApiWorkers
        else -> apiWorkers
    }

    override fun close() {
        // 关闭返回后不再向日志队列提交消息，清缓存的落盘屏障不会漏掉迟滞写入。
        synchronized(logLock) { running.set(false) }
        if (storage.isInitialized()) cleanupResource("文件接口") { storage.value.close() }
        if (network.isInitialized()) cleanupResource("原生网络") { network.value.close() }
        cleanupResource("窗口事件") { events.close() }
        cleanupResource("监听套接字") { server.close() }
        sockets.forEach { socket -> cleanupResource("请求连接") { socket.close() } }
        sockets.clear()
        cleanupResource("资源线程池") { workers.shutdownNow() }
        cleanupResource("接口线程池") { apiWorkers.shutdownNow() }
        cleanupResource("慢接口线程池") { slowApiWorkers.shutdownNow() }
        cleanupResource("控制接口线程池") { controlWorkers.shutdownNow() }
    }
}
