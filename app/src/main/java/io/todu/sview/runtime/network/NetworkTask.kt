package io.todu.sview.runtime.network

import okhttp3.Call
import okhttp3.Response
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

/** 一次请求的流与结果；成功消费、取消、超时和窗口关闭都汇合到释放逻辑。 */
internal class NetworkTask(val id: String, val uploadLength: Long, val destination: DownloadDestination?, private val staging: File, private val temporaryStore: io.todu.sview.storage.TemporaryFiles, private val chunkSize: Int = 65536) : Closeable {
    val upload = NetworkPipe()
    val download = NetworkPipe()
    val progress = DownloadProgress()
    @Volatile var downloadingFile = false; private set
    val metadata = CompletableFuture<JSONObject>()
    val cancelled = AtomicBoolean(false)
    @Volatile var call: Call? = null
    @Volatile var response: Response? = null
    @Volatile var timer: ScheduledFuture<*>? = null
    @Volatile var sent = 0L
    @Volatile var received = 0L
    @Volatile var total = -1L
    @Volatile var result: JSONObject? = null
    @Volatile var errorCode = "CANCELLED"
    private var temporary: File? = null
    private val commitLock = Any()

    fun receive(value: Response) {
        response = value
        if (cancelled.get()) { value.close(); throw IOException("请求已取消") }
        val headers = JSONObject()
        value.headers.names().forEach { headers.put(it.lowercase(), value.headers.values(it).joinToString(", ")) }
        total = value.body?.contentLength() ?: 0
        val target = destination?.takeIf { value.isSuccessful }?.resolve(value)
        downloadingFile = target != null
        metadata.complete(JSONObject().put("status", value.code).put("headers", headers)
            .put("url", value.request.url.toString()).put("total", total)
            .put("downloadPath", destination?.resultPath))
        if (target == null) readBody(value, null) else {
            val output = synchronized(commitLock) {
                check(!cancelled.get())
                val parent = target.parentFile!!
                check(parent.isDirectory || parent.mkdirs()) { "无法创建下载目录" }
                // 设备文件在目标目录暂存，确保最终移动不会跨文件系统。
                temporary = temporaryStore.create(if (destination.device) parent else staging, ".sview-download-", ".part")
                temporary!!.outputStream()
            }
            output.use { readBody(value, it) }
            synchronized(commitLock) {
                check(!cancelled.get())
                // 不覆盖现有文件；仅成功传完才把临时文件发布为最终文件。
                Files.move(temporary!!.toPath(), target.toPath())
                temporaryStore.release(temporary!!)
                temporary = null
            }
        }
        result = JSONObject().put("size", received)
        if (downloadingFile) progress.publish(received, complete = true) else download.end()
    }

    /** 文件直接落盘；流响应每次只向 JS 交付一块，受消费者速度约束。 */
    private fun readBody(value: Response, output: java.io.OutputStream?) {
        value.body?.byteStream()?.use { input ->
            val buffer = ByteArray(chunkSize)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (cancelled.get()) throw IOException("请求已取消")
                received += count
                // put 返回时网页已消费该块，下一次读取可安全复用完整缓冲区。
                if (output == null) download.put(if (count == buffer.size) buffer else buffer.copyOf(count)) else {
                    output.write(buffer, 0, count)
                    // 合并进度不阻塞磁盘写入，网页繁忙不会拖慢下载。
                    progress.publish(received)
                }
            }
            if (total >= 0 && received != total) throw IOException("响应长度不完整")
        }
    }

    fun fail(error: Exception, code: String = "NETWORK_ERROR") {
        errorCode = code
        cancelled.set(true)
        upload.cancel(error)
        download.cancel(error)
        progress.cancel(error)
        metadata.completeExceptionally(error)
        io.todu.sview.runtime.cleanupResource("HTTP 请求") { call?.cancel() }
        io.todu.sview.runtime.cleanupResource("HTTP 响应") { response?.close() }
        timer?.cancel(false)
        synchronized(commitLock) { temporary?.let(temporaryStore::release); temporary = null }
    }

    override fun close() { fail(IOException("请求已取消"), "CANCELLED") }
}
