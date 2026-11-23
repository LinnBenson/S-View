package io.todu.sview.runtime.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import io.todu.sview.data.ProjectRepository
import io.todu.sview.runtime.WebMime
import io.todu.sview.runtime.storage.StoragePaths
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** 自定义网页先读取项目原始资源，未命中文件的请求才转给创建服务器的脚本。 */
internal class CustomHttpSession(
    private val context: Context, private val repository: ProjectRepository, private val projectId: String,
    val id: String, options: JSONObject, private val event: (JSONObject) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()
    private val address = LanAddress.resolve(context, options.optString("server", ""))
    private val timeout = integer(options, "timeout", 30000, 1000..300000)
    private val bodyLimit = integer(options, "maxBodySizeMB", 16, 1..512) * 1048576L
    private val concurrency = integer(options, "maxConcurrentRequests", 4, 1..16)
    private val hasHandler = options.optBoolean("hasHandler")
    private val root = repository.root(projectId)
    private val homePath = options.getString("home")
    private val directory = File(context.cacheDir, "custom-servers/$id")
    private val pending = ConcurrentHashMap<String, CustomHttpExchange>()
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var watching = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) = checkAddress()
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) = checkAddress()
    }
    private val transport: LanHttpTransport

    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) { "服务标识无效" }
        val home = resolveHome()
        require(home.isFile && home.canRead() && home.extension.lowercase() in listOf("html", "htm")) { "home 应为可读取的 HTML 文件" }
        val port = integer(options, "port", 8080, 1024..65535)
        check(directory.mkdirs()) { "无法创建请求缓存" }
        try {
            transport = LanHttpTransport(address, port, ::route, LanHttpTransport.Configuration(concurrency, timeout, true)) {
                fail(it)
            }
        } catch (error: Exception) { directory.deleteRecursively(); throw error }
    }

    fun start(): JSONObject {
        try {
            val host = if (address.isAnyLocalAddress) LanAddress.resolve(context, "").hostAddress else address.hostAddress
            watching = true
            manager.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
            check(!isClosed) { "服务已停止" }
            transport.start()
            return JSONObject().put("id", id).put("server", address.hostAddress).put("port", transport.port)
                .put("url", "http://$host:${transport.port}")
        } catch (error: Exception) { close(); throw error }
    }

    /** 设备首页每次读取前重新检查授权和路径，不把其所在目录作为公开资源目录。 */
    private fun resolveHome(): File = if (homePath.startsWith("device:")) {
        StoragePaths(root, repository::deviceStorageRoot).resolve(homePath).file
    } else repository.scoped(root, homePath)

    /** 未指定通配监听时，网卡地址消失即停止，保持与其他局域网接口一致。 */
    private fun checkAddress() {
        if (isClosed || address.isAnyLocalAddress) return
        if (runCatching { LanAddress.resolve(context, address.hostAddress!!) }.isFailure) fail(IllegalStateException("监听网卡已断开或地址已改变"))
    }

    private fun fail(error: Exception) {
        if (isClosed) return
        emit("error", JSONObject().put("message", error.message ?: "服务器已停止"))
        close()
    }

    private fun route(request: LanHttpTransport.Request, reply: LanHttpTransport.Reply) {
        check(!isClosed) { "服务器已关闭" }
        if (request.length > bodyLimit) {
            reply.bytes("请求正文过大".toByteArray(), "text/plain; charset=utf-8", 413)
            emit("error", JSONObject().put("message", "请求正文超过大小限制").put("code", "PAYLOAD_TOO_LARGE"))
            return
        }
        val path = URLDecoder.decode(request.path.replace("+", "%2B"), "UTF-8")
        require(!path.contains('\u0000') && !path.contains('\\') && path.split('/').none { it == ".." }) { "资源路径无效" }
        val file = if (path == "/") resolveHome() else repository.scoped(root, path)
        if (path == "/" || file.isFile) {
            when {
                request.method !in listOf("GET", "HEAD") -> reply.bytes(byteArrayOf(), "text/plain", 405, "Allow: GET, HEAD\r\n")
                !file.isFile -> reply.bytes("首页文件不存在".toByteArray(), "text/plain; charset=utf-8", 404)
                else -> reply.file(file, WebMime.of(file), file.name, false)
            }
            return
        }
        if (!hasHandler) { reply.bytes("未找到".toByteArray(), "text/plain; charset=utf-8", 404); return }
        handle(request, reply)
    }

    /** 请求正文和响应正文分别落盘；等待脚本时不占用系统接口工作线程。 */
    private fun handle(request: LanHttpTransport.Request, reply: LanHttpTransport.Reply) {
        val exchange = CustomHttpExchange(directory, request.deadlineNanos)
        pending[exchange.id] = exchange
        var cancelled = false
        try {
            check(!isClosed) { "服务器已关闭" }
            exchange.receive(request.input, request.length, bodyLimit)
            val value = JSONObject().put("requestId", exchange.id).put("method", request.method)
                .put("url", "http://${request.headers.getValue("host")}${request.target}")
                .put("headers", JSONObject(request.headers)).put("hasBody", request.method !in listOf("GET", "HEAD"))
            emit("request", value)
            val response = exchange.await()
            reply.customFile(exchange.responseFile, response.status, response.headers)
        } catch (error: Exception) {
            val cause = if (error is ExecutionException) error.cause as? Exception ?: error else error
            val timeout = cause is TimeoutException || cause is java.net.SocketTimeoutException || (cause is LanTransferInterruptedException && System.nanoTime() >= request.deadlineNanos)
            val status = if (timeout) 504 else (cause as? CustomHttpExchange.Failure)?.status ?: 500
            val code = if (timeout) "TIMEOUT" else (cause as? CustomHttpExchange.Failure)?.code ?: "REQUEST_FAILED"
            if (!isClosed) {
                if (timeout || cause is LanTransferInterruptedException) {
                    // 请求级中断只取消当前请求，不作为整个服务的运行错误。
                    cancelled = true
                } else {
                    emit("error", JSONObject().put("message", cause.message ?: "请求处理失败").put("code", code).put("requestId", exchange.id))
                }
                runCatching { if (!reply.started) reply.bytes(if (status == 413) "请求正文过大".toByteArray() else if (status == 504) "请求处理超时".toByteArray() else "请求处理失败".toByteArray(), "text/plain; charset=utf-8", status) }
            }
        } finally {
            pending.remove(exchange.id)
            emit("request-end", JSONObject().put("requestId", exchange.id))
            exchange.close()
            if (cancelled) emit("cancelled", exchange.id)
        }
    }

    fun exchange(requestId: String): CustomHttpExchange = pending[requestId] ?: error("请求已结束")

    private fun emit(kind: String, value: Any) {
        if (!isClosed) event(JSONObject().put("id", id).put("kind", kind).put("value", value))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (watching) runCatching { manager.unregisterNetworkCallback(networkCallback) }
        pending.values.forEach { it.fail(IllegalStateException("服务器已关闭")) }
        transport.close()
        pending.values.forEach { it.close() }
        pending.clear()
        directory.deleteRecursively()
        event(JSONObject().put("id", id).put("kind", "closed"))
    }

    companion object {
        private fun integer(options: JSONObject, key: String, fallback: Int, range: IntRange): Int {
            val value = options.optInt(key, fallback)
            require(value in range) { "$key 应为 ${range.first}–${range.last}" }
            return value
        }
    }
}
