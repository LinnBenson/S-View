package io.todu.sview.runtime.network

import io.todu.sview.data.ProjectRepository
import io.todu.sview.runtime.cleanupResource
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 原生 HTTP 客户端，不依赖网页跨域策略；每个窗口独立持有任务和连接池。 */
class NativeNetwork(private val repository: ProjectRepository, private val projectId: String) : Closeable {
    private val tasks = ConcurrentHashMap<String, NetworkTask>()
    private val workers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue()).apply { allowCoreThreadTimeOut(true) }
    private val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val cookieStore = lazy { ProjectCookieJar(repository.networkCookies(projectId)) }
    private val cookies get() = cookieStore.value
    private val failures = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Pair<String, Exception>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Exception>>?) = size > 32
    })
    private val staging by lazy {
        java.io.File(repository.networkCookies(projectId).parentFile, "downloads").apply {
            mkdirs()
            // 上次进程被系统直接杀死时遗留的半成品不进入项目资源目录。
            listFiles()?.forEach { it.delete() }
        }
    }
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).writeTimeout(0, TimeUnit.SECONDS).build()
    private var closed = false

    /** 建立任务后即可逐块上传；返回前完成校验，避免无效请求占用后台线程。 */
    @Synchronized fun create(config: JSONObject): JSONObject {
        check(!closed) { "运行窗口已关闭" }
        check(tasks.size < 4) { "最多同时运行四个网络请求，请等待或取消已有请求" }
        val url = config.getString("url").toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) { "请通过 Authorization 请求头传入凭据" }
        val method = config.optString("method", "GET").uppercase()
        require(method.matches(Regex("[A-Z]+"))) { "请求方法无效" }
        val timeout = config.optLong("timeout", 15000)
        require(timeout in 0..Int.MAX_VALUE.toLong()) { "超时毫秒数无效" }
        val hasBody = config.optBoolean("hasBody")
        require(!hasBody || method !in setOf("GET", "HEAD")) { "GET 和 HEAD 不能设置请求体" }
        val size = config.optLong("size", -1)
        require(size >= -1) { "请求体长度无效" }
        val path = config.optString("download")
        val target = if (path.isEmpty()) null else {
            val device = path.startsWith("device:/")
            require(!path.startsWith("device:") || device) { "设备路径应以 device:/ 开头" }
            DownloadDestination(if (device) repository.deviceStorageRoot() else repository.root(projectId), path, device)
        }
        val responseType = config.optString("responseType", "auto")
        require(responseType in setOf("auto", "json", "text", "blob", "arrayBuffer", "stream")) { "响应格式无效" }
        val task = NetworkTask(UUID.randomUUID().toString(), size, target, staging, repository.temporaryFiles,
            repository.transferChunkSize, coalesceResponse = responseType != "stream")
        val builder = Request.Builder().url(url)
        val headers = config.optJSONObject("headers") ?: JSONObject()
        headers.keys().forEach { name ->
            // 长度和连接分帧由客户端生成，不能让用户头破坏消息边界。
            require(name.lowercase() !in setOf("content-length", "transfer-encoding", "connection")) { "该请求头由客户端管理：$name" }
            builder.header(name, headers.getString(name))
        }
        val body = if (hasBody) object : RequestBody() {
            override fun contentType() = headers.optString("content-type").takeIf { it.isNotEmpty() }?.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                while (true) {
                    val block = task.upload.take() ?: break
                    sink.write(block.bytes)
                    sink.flush()
                    task.sent += block.bytes.size
                    task.upload.consumed(block)
                }
                if (size >= 0 && task.sent != size) throw IOException("上传长度与声明不符")
            }
        } else if (method in setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")) object : RequestBody() {
            override fun contentType() = null
            override fun contentLength() = 0L
            override fun writeTo(sink: BufferedSink) = Unit
        } else null
        val request = builder.method(method, body).build()
        val useCookies = config.optBoolean("cookies", true)
        val jar = if (useCookies) cookies else null
        val explicit = request.header("Cookie")
        val scopedClient = client.newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).addNetworkInterceptor { chain ->
            val current = chain.request()
            val custom = if (current.url.host == url.host && current.url.scheme == url.scheme && current.url.port == url.port) explicit else null
            val merged = linkedMapOf<String, String>()
            jar?.loadForRequest(current.url)?.forEach { merged[it.name] = it.value }
            custom?.split(';')?.forEach { item ->
                val separator = item.indexOf('=')
                if (separator > 0) merged[item.substring(0, separator).trim()] = item.substring(separator + 1).trim()
            }
            val outgoing = current.newBuilder().removeHeader("Cookie")
            if (merged.isNotEmpty()) outgoing.header("Cookie", merged.entries.joinToString("; ") { "${it.key}=${it.value}" })
            val response = chain.proceed(outgoing.build())
            jar?.saveFromResponse(current.url, Cookie.parseAll(current.url, response.headers))
            response
        }.build()
        task.call = scopedClient.newCall(request)
        tasks[task.id] = task
        if (timeout > 0) task.timer = deadlines.schedule({ fail(task, IOException("请求超时"), "TIMEOUT") }, timeout, TimeUnit.MILLISECONDS)
        workers.execute {
            try { task.call!!.execute().use { task.receive(it) } }
            catch (error: Exception) { if (!task.cancelled.get()) fail(task, error, "NETWORK_ERROR") }
        }
        return JSONObject().put("id", task.id)
    }

    fun metadata(id: String): JSONObject = task(id).metadata.get()
    fun upload(id: String, bytes: ByteArray, end: Boolean): JSONObject {
        val task = task(id)
        if (end) task.upload.end() else task.upload.put(bytes)
        return JSONObject().put("loaded", task.sent).put("total", task.uploadLength)
    }

    /** 返回一块数据及当前统计，响应成功写回后才确认消费。 */
    fun read(id: String, send: (ByteArray, Long, Long, Boolean, Boolean) -> Unit) {
        val task = task(id)
        if (task.downloadingFile) {
            val progress = task.progress.take()
            send(byteArrayOf(), progress.loaded, task.total, progress.ended, true)
            if (progress.ended) { tasks.remove(id); task.timer?.cancel(false) }
            return
        }
        val block = task.download.take()
        send(block?.bytes ?: ByteArray(0), task.received, task.total, block == null, task.destination != null)
        if (block != null) task.download.consumed(block) else {
            tasks.remove(id)
            task.timer?.cancel(false)
        }
    }

    private fun fail(task: NetworkTask, error: Exception, code: String) {
        failures[task.id] = code to error
        tasks.remove(task.id)
        task.fail(error, code)
    }

    fun cancel(id: String) {
        tasks.remove(id)?.let { cleanupResource("网络请求") { it.close() } }
        failures.remove(id)
    }
    fun errorCode(id: String) = failures[id]?.first ?: tasks[id]?.errorCode ?: "NETWORK_ERROR"
    private fun task(id: String): NetworkTask = tasks[id] ?: throw (failures[id]?.second ?: IOException("请求不存在或已结束"))

    /** 项目文件通过受控只读接口传给 JS，不暴露应用私有目录。 */
    fun file(path: String) = repository.scoped(repository.root(projectId), path).also { require(it.isFile) { "项目文件不存在" } }

    @Synchronized override fun close() {
        closed = true
        tasks.keys.toList().forEach(::cancel)
        failures.clear()
        if (cookieStore.isInitialized()) cleanupResource("网络 Cookie") { cookies.close() }
        cleanupResource("网络任务线程") { workers.shutdownNow() }
        cleanupResource("网络超时任务") { deadlines.shutdownNow() }
        cleanupResource("网络连接池") { client.connectionPool.evictAll() }
        cleanupResource("网络调度器") { client.dispatcher.executorService.shutdownNow() }
    }
}
