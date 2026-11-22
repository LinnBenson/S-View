package io.todu.sview.runtime

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.Closeable
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.Executors

/** 空闲不轮询；保存有限历史供断线重放，写线程不在持有状态锁时访问套接字。 */
class WindowEvents(private val release: (Socket) -> Unit = {}) : Closeable {
    private data class Event(val id: Long, val name: String, val data: String)
    private class Client(val socket: Socket, var cursor: Long, var header: Boolean = true)
    private val lock = Any()
    private val writes = Executors.newSingleThreadExecutor { task -> Thread(task, "窗口事件发送").apply { isDaemon = true } }
    private val history = ArrayDeque<Event>()
    private var sequence = 0L
    private var retainedBytes = 0
    private var client: Client? = null
    private var draining = false
    private var ready = false
    private var ending = false
    private var disposed = false
    private val closeAcknowledged = CompletableDeferred<Unit>()

    /** 旧连接先关闭，解除可能阻塞的写入；新连接根据网页已接收的序号继续。 */
    fun attach(socket: Socket, after: Long = 0) {
        val previous = synchronized(lock) {
            check(!disposed)
            val previous = client
            client = Client(socket, after.coerceIn(0, sequence))
            schedule()
            previous
        }
        previous?.let { disconnect(it.socket) }
    }

    fun locationChanged(location: String) = publish("location", JSONObject().put("location", location).toString())
    fun marginChanged(margins: io.todu.sview.data.WindowMargins) = publish("margin", margins.toJson().toString())
    fun cornerRadiusChanged(radius: Double) = publish("cornerRadius", JSONObject().put("cornerRadius", radius).toString())
    fun localServerEvent(event: JSONObject) = publish("local-server", event.toString())
    fun nfcEvent(event: JSONObject) = publish("nfc", event.toString())
    fun pageStarted() = synchronized(lock) { ready = false }
    fun pageReady() = synchronized(lock) {
        if (!ready && !ending && !disposed) { ready = true; publish("ready", "{}") }
    }
    fun pageError(message: String) = publish("runtime-error", JSONObject().put("type", "load").put("message", message).toString())

    /** 页面不能阻止窗口退出；关闭事件也会在重连时补发。 */
    fun beginClose(): CompletableDeferred<Unit> = synchronized(lock) {
        if (!ending) {
            publish("close", "{}")
            ending = true
            if (client == null) closeAcknowledged.complete(Unit)
        }
        closeAcknowledged
    }
    fun acknowledgeClose() = synchronized(lock) { if (ending) closeAcknowledged.complete(Unit); Unit }

    private fun publish(name: String, data: String) = synchronized(lock) {
        if (disposed || ending) return@synchronized
        history.addLast(Event(++sequence, name, data))
        retainedBytes += data.length * 2
        // 历史有界，长时间离线超过保留范围时显式通知缺口，绝不静默跳过。
        while (history.size > 512 || retainedBytes > 2 * 1024 * 1024) {
            retainedBytes -= history.removeFirst().data.length * 2
        }
        schedule()
    }

    /** 任何数量的事件只安排一个排空任务，消除逐条提交时队列溢出丢事件的问题。 */
    private fun schedule() {
        if (draining || disposed || client == null) return
        draining = true
        writes.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val batch = synchronized(lock) {
                val target = client
                if (disposed || target == null) { draining = false; return }
                val first = history.peekFirst()?.id ?: sequence + 1
                val gap = target.cursor < first - 1
                val pending = history.filter { it.id > target.cursor }
                if (!target.header && !gap && pending.isEmpty()) { draining = false; return }
                val text = buildString {
                    if (target.header) {
                        append("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\nConnection: keep-alive\r\nX-Content-Type-Options: nosniff\r\n\r\n: connected\n\n")
                        // 状态事件不带序号，不干扰历史去重。
                        if (ready) append("event: ready\ndata: {}\n\n")
                        if (ending) append("event: close\ndata: {}\n\n")
                    }
                    if (gap) append("event: runtime-error\ndata: {\"code\":\"EVENT_GAP\",\"type\":\"connection\",\"message\":\"事件连接中断过久，部分历史已过期\"}\n\n")
                    pending.forEach { append("id: ${it.id}\nevent: ${it.name}\ndata: ${it.data}\n\n") }
                }
                Triple(target, text, pending.lastOrNull()?.id ?: sequence)
            }
            try {
                batch.first.socket.getOutputStream().apply { write(batch.second.toByteArray()); flush() }
                synchronized(lock) { batch.first.header = false; batch.first.cursor = batch.third }
            } catch (error: Exception) {
                android.util.Log.w("SViewEvents", "事件连接已断开，保留历史等待重连", error)
                synchronized(lock) { if (client === batch.first) client = null }
                disconnect(batch.first.socket)
            }
        }
    }

    private fun disconnect(socket: Socket) {
        cleanupResource("事件连接") { socket.close() }
        cleanupResource("事件连接登记") { release(socket) }
    }

    override fun close() {
        val previous = synchronized(lock) {
            disposed = true
            closeAcknowledged.complete(Unit)
            history.clear(); retainedBytes = 0
            client.also { client = null }
        }
        previous?.let { disconnect(it.socket) }
        writes.shutdownNow()
    }
}
