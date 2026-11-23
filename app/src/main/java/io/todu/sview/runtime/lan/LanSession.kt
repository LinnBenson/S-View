package io.todu.sview.runtime.lan

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import io.todu.sview.storage.DeviceStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** 聊天与接收页面共用传输层；每个实例独立拥有消息、文件与客户端身份。 */
internal class LanSession(private val context: Context, val id: String, private val chat: Boolean,
    options: JSONObject, private val event: (JSONObject) -> Unit) : Closeable {
    private val lock = Any()
    private val upload = Semaphore(1)
    private val polls = Semaphore(6)
    private val token = options.optString("token", "")
    private val name = options.optString("name", if (chat) "S View 聊天" else "S View 文件传输")
    private val requestedAddress = options.optString("server", "")
    private val address = LanAddress.resolve(context, requestedAddress)
    private val maxFile = limit(options, "maxFileSizeMB", 512)
    private val maxImage = limit(options, "maxImageSizeMB", 512)
    private val temporary = File(context.cacheDir, "lan-sessions/$id")
    private val partials = ConcurrentHashMap.newKeySet<File>()
    private val clients = mutableMapOf<String, String>()
    private val messages = mutableListOf<JSONObject>()
    private val attachments = mutableMapOf<Long, File>()
    private var storedBytes = 0L
    @Volatile private var changed = java.util.concurrent.CompletableFuture<Unit>()
    val isClosed: Boolean get() = closed
    private val networkManager = context.getSystemService(ConnectivityManager::class.java)
    @Suppress("DEPRECATION")
    private val boundNetwork = networkManager.allNetworks.firstOrNull { network ->
        networkManager.getLinkProperties(network)?.linkAddresses?.any { it.address == address } == true
    }
    private var watching = false
    @Volatile private var closed = false
    private val server: LanHttpTransport
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (network == boundNetwork && !closed) fail(IllegalStateException("监听网卡已断开")) else checkAddress()
        }
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) = checkAddress()
    }

    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) { "服务标识无效" }
        require(name.length in 1..80 && !name.any { it.isISOControl() }) { "名称长度应为 1–80 个字符" }
        require(token.length <= 64 && token.all { it.code in 33..126 }) { "访问码最多 64 个可见 ASCII 字符" }
        val port = options.optInt("port", 8080)
        require(port in 1024..65535) { "端口应为 1024–65535" }
        if (!chat) DeviceStorage.root(context)
        check(temporary.mkdirs()) { "无法创建服务缓存" }
        try { server = LanHttpTransport(address, port, ::route) { fail(it) } }
        catch (error: Exception) { temporary.deleteRecursively(); throw error }
    }

    /** 监听建立后才对外返回；地址变化时终止原会话，防止悄悄改绑网卡。 */
    fun start(): JSONObject {
        try {
            watching = true
            networkManager.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
            check(!closed) { "监听网卡已断开" }
            server.start()
            return JSONObject().put("id", id).put("name", name).put("port", server.port)
                .put("token", token)
                .put("url", "http://${if (address.isAnyLocalAddress) LanAddress.resolve(context, "").hostAddress else address.hostAddress}:${server.port}")
        } catch (error: Exception) { close(); throw error }
    }

    private fun checkAddress() {
        if (closed || address.isAnyLocalAddress) return
        val same = runCatching { LanAddress.resolve(context, if (requestedAddress.isEmpty()) "" else address.hostAddress!!) == address }.getOrDefault(false)
        if (!same) fail(IllegalStateException("监听网卡已断开或地址已改变"))
    }

    private fun fail(error: Exception) {
        if (!closed) { emit("error", JSONObject().put("message", error.message ?: "服务已停止")); close() }
    }

    private fun emit(kind: String, value: Any) {
        if (!closed) event(JSONObject().put("id", id).put("kind", kind).put("value", value))
    }

    private fun route(request: LanHttpTransport.Request, reply: LanHttpTransport.Reply) {
        check(!closed) { "服务已关闭" }
        if (request.path == "/" && request.method == "GET") { page(reply); return }
        if (request.path == "/favicon.ico") { reply.bytes(ByteArray(0), "image/x-icon"); return }
        if (request.path == (if (chat) "/join" else "/auth") && request.method == "POST") {
            authenticate(request)
            if (!chat) { reply.json(); return }
            val cookie = cookie(request) ?: UUID.randomUUID().toString()
            val identity = synchronized(lock) {
                check(clients.size < 64 || clients.containsKey(cookie)) { "聊天人数已达上限" }
                clients.getOrPut(cookie) { "访客-" + UUID.randomUUID().toString().take(8) }
            }
            reply.json(JSONObject().put("id", identity).put("sessionId", id), cookie = "Set-Cookie: sview_chat_$id=$cookie; HttpOnly; SameSite=Strict; Path=/\r\n")
            return
        }
        val sender = if (chat) synchronized(lock) { clients[cookie(request)] } ?: throw LanHttpTransport.Unauthorized()
            else { authenticate(request); "" }
        when {
            chat && request.path == "/messages" && request.method == "GET" -> {
                val after = request.query["after"]?.toLongOrNull() ?: 0
                // 空闲时等待新消息，减少手表被短轮询唤醒；写响应不持有会话锁。
                val signal = synchronized(lock) { if (messages.size <= after) changed else null }
                if (signal != null) {
                    check(polls.tryAcquire()) { "连接繁忙，请稍后重试" }
                    try { signal.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: java.util.concurrent.TimeoutException) { }
                    finally { polls.release() }
                }
                val value = synchronized(lock) {
                    check(!closed) { "服务已关闭" }
                    val values = messages.filter { it.getLong("id") > after }.take(100)
                    val cursor = values.lastOrNull()?.getLong("id") ?: after
                    JSONObject().put("sessionId", id).put("messages", JSONArray(values)).put("cursor", cursor)
                        .put("more", messages.lastOrNull()?.getLong("id")?.let { it > cursor } ?: false)
                }
                reply.json(value)
            }
            chat && request.path.startsWith("/attachment/") && request.method == "GET" -> {
                val index = request.path.substringAfterLast('/').toLongOrNull() ?: error("文件不存在")
                val pair = synchronized(lock) { (attachments[index] ?: error("文件不存在")) to messages.first { it.getLong("id") == index } }
                val image = pair.second.getString("type") == "image"
                reply.file(pair.first, if (image) imageType(pair.first) else "application/octet-stream", pair.second.getString("name"), !image || request.query["download"] == "1")
            }
            chat && request.path == "/leave" && request.method == "POST" -> {
                synchronized(lock) { clients.remove(cookie(request)) }; reply.json()
            }
            request.path == (if (chat) "/send" else "/text") && request.method == "POST" -> {
                val text = request.text().trim()
                require(text.isNotEmpty() && text.length <= 2000) { "文字长度应为 1–2000 个字符" }
                if (chat) reply.json(message(sender, "text", text = text))
                else { emit("text", text); reply.json() }
            }
            request.method == "POST" && request.path in listOf("/image", if (chat) "/file" else "/upload") -> receive(request, reply, sender)
            else -> reply.json(JSONObject().put("message", "接口不存在"), 404)
        }
    }

    private fun authenticate(request: LanHttpTransport.Request) {
        val actual = request.headers["x-sview-token"] ?: ""
        if (!java.security.MessageDigest.isEqual(token.toByteArray(), actual.toByteArray())) throw LanHttpTransport.Unauthorized()
    }

    private fun cookie(request: LanHttpTransport.Request): String? = request.headers["cookie"]?.split(';')
        ?.map { it.trim() }?.firstOrNull { it.startsWith("sview_chat_$id=") }?.substringAfter('=')

    private fun page(reply: LanHttpTransport.Reply) {
        val escaped = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
        val html = context.assets.open("lan/${if (chat) "chat" else "upload"}.html").bufferedReader().use { it.readText() }
            .replace("__SERVER_NAME__", escaped).replace("__REQUIRES_TOKEN__", token.isNotEmpty().toString())
            .replace("__MAX_FILE_MB__", (maxFile / 1048576).toString()).replace("__MAX_IMAGE_MB__", (maxImage / 1048576).toString())
        reply.bytes(html.toByteArray(), "text/html; charset=utf-8", extra = "Content-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' blob: data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'\r\n")
    }

    /** 系统消息不回调 onMessage，避免脚本自动回复自身。 */
    fun send(text: String): JSONObject {
        require(chat) { "接收服务不支持 send" }
        require(text.isNotBlank() && text.length <= 2000) { "文字长度应为 1–2000 个字符" }
        return message("系统", "text", text = text, system = true)
    }

    private fun message(sender: String, type: String, text: String? = null, file: File? = null, name: String? = null, system: Boolean = false): JSONObject = synchronized(lock) {
        check(!closed) { "服务已关闭" }
        check(messages.size < 10000) { "聊天记录已达上限，请重新启动服务" }
        val index = messages.size.toLong() + 1
        val value = JSONObject().put("id", index).put("senderId", sender).put("type", type).put("time", System.currentTimeMillis())
            .put("text", text ?: JSONObject.NULL).put("name", name ?: JSONObject.NULL).put("size", file?.length() ?: JSONObject.NULL).put("system", system)
        if (file != null) { attachments[index] = file; storedBytes += file.length() }
        messages.add(value)
        val previous = changed; changed = java.util.concurrent.CompletableFuture(); previous.complete(Unit)
        if (!system) emit("message", value)
        value
    }

    private fun receive(request: LanHttpTransport.Request, reply: LanHttpTransport.Reply, sender: String) {
        check(upload.tryAcquire()) { "已有文件正在上传，请稍后重试" }
        // 每次上传生成独立 ID，接收进度和保存结果共用此 ID。
        val transferId = UUID.randomUUID().toString()
        var file: File? = null
        var committed = false
        var cancelled = false
        try {
            val image = request.path == "/image"
            val name = request.query["name"] ?: error("缺少文件名")
            require(name.isNotBlank() && name !in listOf(".", "..") && name.length <= 120 && name.toByteArray().size <= 240 && !name.any { it == '/' || it == '\\' || it.isISOControl() }) { "文件名无效" }
            require(request.length in 0..(if (image) maxImage else maxFile)) { "文件超过大小限制" }
            if (chat) synchronized(lock) { check(storedBytes + request.length <= 512L * 1048576 && messages.size < 10000) { "聊天缓存已满" } }
            val pending = if (chat) File(temporary, "$transferId.part")
                else File(downloadDirectory(), ".sview-transfer-$transferId.part")
            file = pending
            partials.add(pending)
            val kind = if (image) "image" else "file"
            if (!chat) progress(kind, transferId, 0, false)
            val started = android.os.SystemClock.elapsedRealtime()
            var last = started
            var received = 0L
            pending.outputStream().use { output ->
                val buffer = ByteArray(32768)
                while (received < request.length) {
                    check(!closed) { "服务已关闭" }
                    if (android.os.SystemClock.elapsedRealtime() - started >= 1800000) throw LanTransferInterruptedException()
                    val count = LanTransferIo.read(request.input, buffer, minOf(buffer.size.toLong(), request.length - received).toInt())
                    if (count < 0) throw LanTransferInterruptedException()
                    output.write(buffer, 0, count)
                    received += count
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!chat && now - last >= 1000) { progress(kind, transferId, minOf(99, (received * 100 / request.length).toInt()), false); last = now }
                }
                output.fd.sync()
            }
            if (image) imageType(pending)
            if (chat) {
                val value = message(sender, kind, file = pending, name = name)
                partials.remove(pending)
                committed = true
                reply.json(value)
            } else {
                val saved = saveDownload(pending, name)
                committed = true
                progress(kind, transferId, 100, "device:/Download/${saved.name}")
                reply.json(JSONObject().put("name", saved.name).put("size", saved.length()))
            }
        } catch (error: Exception) {
            if (error is LanTransferInterruptedException) {
                // 单次传输中断不等于服务故障，保留监听并通知对应上传 ID。
                cancelled = !committed
            } else {
                emit("error", JSONObject().put("message", error.message ?: "上传失败"))
                throw error
            }
        } finally {
            file?.let { if (partials.remove(it)) it.delete() }
            upload.release()
            if (closed) temporary.deleteRecursively()
            if (cancelled) emit("cancelled", transferId)
        }
    }

    /** 将同一次上传的 ID 连同进度和保存路径传给脚本。 */
    private fun progress(kind: String, id: String, percent: Int, path: Any) =
        emit(kind, JSONObject().put("id", id).put("percent", percent).put("path", path))

    /** 上传服务直接写入 Download 临时文件，完成后只改名，不再复制整份文件。 */
    private fun downloadDirectory(): File = File(DeviceStorage.root(context), "Download").apply {
        check(isDirectory || mkdirs()) { "无法创建 Download" }
    }

    /** 与其他上传会话串行选择名称，保留同名文件并在关闭时阻止提交。 */
    private fun saveDownload(pending: File, name: String): File = synchronized(downloadLock) {
        synchronized(lock) {
            check(!closed) { "服务已关闭" }
            val directory = pending.parentFile!!
            var destination = File(directory, name)
            var suffix = 1
            while (destination.exists()) {
                val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
                destination = File(directory, name.take(dot) + " (${suffix++})" + name.drop(dot))
            }
            check(pending.renameTo(destination)) { "无法保存文件到 Download" }
            partials.remove(pending)
            destination
        }
    }

    private fun imageType(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in listOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/heif", "image/avif")) { "图片格式不受支持" }
        return bounds.outMimeType
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; changed.complete(Unit); clients.clear(); messages.clear(); attachments.clear() }
        server.close()
        if (watching) runCatching { networkManager.unregisterNetworkCallback(networkCallback) }
        partials.forEach { it.delete() }
        temporary.deleteRecursively()
        event(JSONObject().put("id", id).put("kind", "closed"))
    }

    companion object {
        private val downloadLock = Any()
        private fun limit(options: JSONObject, key: String, default: Int): Long {
            val value = options.optInt(key, default)
            require(value in 1..512) { "$key 应为 1–512 MB" }
            return value * 1048576L
        }
    }
}
