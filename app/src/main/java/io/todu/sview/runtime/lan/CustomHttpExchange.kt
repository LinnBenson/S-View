package io.todu.sview.runtime.lan

import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 一次请求独占临时文件；大正文不进入事件历史，也不使用 Base64。 */
internal class CustomHttpExchange(directory: File, private val deadline: Long) : Closeable {
    val id: String = UUID.randomUUID().toString()
    val requestFile = File(directory, "$id.request")
    val responseFile = File(directory, "$id.response")
    private val result = CompletableFuture<Response>()
    private var metadata: Response? = null
    private var closed = false
    private var completed = false

    data class Response(val status: Int, val headers: List<Pair<String, String>>)
    class Failure(val status: Int, val code: String, message: String) : Exception(message)

    /** 接收完成再通知脚本，避免脚本未读取正文时占住客户端连接。 */
    fun receive(input: InputStream, length: Long, limit: Long) {
        if (length > limit) throw Failure(413, "PAYLOAD_TOO_LARGE", "请求正文超过大小限制")
        val stream = synchronized(this) { ensureOpen(); requestFile.outputStream() }
        stream.use { output ->
            val bytes = ByteArray(32768)
            var received = 0L
            while (length < 0 || received < length) {
                ensureTime()
                val count = input.read(bytes, 0, if (length < 0) bytes.size else minOf(bytes.size.toLong(), length - received).toInt())
                if (count < 0 && length < 0) break
                check(count > 0) { "请求内容不完整" }
                received += count
                if (received > limit) throw Failure(413, "PAYLOAD_TOO_LARGE", "请求正文超过大小限制")
                output.write(bytes, 0, count)
            }
        }
    }

    @Synchronized
    fun begin(value: JSONObject) {
        ensureOpen()
        check(metadata == null) { "响应已经开始" }
        val status = value.getInt("status")
        require(status in 200..599) { "响应状态码无效" }
        val values = value.getJSONArray("headers")
        require(values.length() <= 100) { "响应头过多" }
        val fields = (0 until values.length()).map { index ->
            val pair = values.getJSONArray(index)
            val name = pair.getString(0)
            val content = pair.getString(1)
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "响应头名称无效" }
            require(content.all { it == '\t' || it.code in 32..255 && it.code != 127 }) { "响应头内容无效" }
            name to content
        }
        require(fields.sumOf { it.first.length + it.second.length + 4 } <= 16384) { "响应头过大" }
        val connectionFields = fields.filter { it.first.equals("connection", true) }
            .flatMap { it.second.lowercase().split(',').map(String::trim) }
        val managed = setOf("content-length", "transfer-encoding", "connection", "keep-alive", "trailer", "upgrade", "proxy-connection") + connectionFields
        metadata = Response(status, fields.filter { it.first.lowercase() !in managed })
        responseFile.writeBytes(byteArrayOf())
    }

    @Synchronized
    fun append(bytes: ByteArray) {
        ensureOpen()
        check(metadata != null) { "响应尚未开始" }
        responseFile.appendBytes(bytes)
    }

    @Synchronized
    fun complete() {
        ensureOpen()
        val response = checkNotNull(metadata) { "响应尚未开始" }
        completed = true
        result.complete(response)
    }

    fun fail(error: Exception) { result.completeExceptionally(error) }

    fun await(): Response {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw TimeoutException("请求处理超时")
        return result.get(remaining, TimeUnit.NANOSECONDS)
    }

    private fun ensureTime() {
        if (System.nanoTime() >= deadline) throw TimeoutException("请求处理超时")
        check(!result.isCompletedExceptionally) { "请求已取消" }
    }

    private fun ensureOpen() {
        check(!closed && !completed) { "请求已结束" }
        ensureTime()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        result.completeExceptionally(IllegalStateException("请求已结束"))
        requestFile.delete()
        responseFile.delete()
    }
}
