package io.todu.sview.runtime.scan

import android.system.ErrnoException
import android.system.OsConstants
import io.todu.sview.runtime.api.ApiErrors
import io.todu.sview.runtime.api.ApiException
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** 有界结果队列对扫描施加背压；结束汇总独立保存，不会被满队列阻塞。 */
internal class PortScanTask(private val options: PortScanOptions) {
    private val events = ArrayBlockingQueue<JSONObject>(64)
    private val sockets = mutableSetOf<Socket>()
    private val counts = linkedMapOf("open" to 0, "closed" to 0, "timeout" to 0, "unreachable" to 0, "error" to 0)
    private val openPorts = sortedSetOf<Int>()
    private val cursor = AtomicInteger()
    private val startedAt = System.nanoTime()
    private var completed = 0
    private var address: String? = null
    @Volatile private var stopping = false
    @Volatile private var lastRead = System.nanoTime()
    @Volatile private var failure: JSONObject? = null
    val completion = CompletableFuture<JSONObject>()

    /** 每个窗口只允许一个任务，因此协调线程和探测线程数量始终有界。 */
    fun start() { Thread(::run, "sview-port-scan").start() }

    fun stop() {
        synchronized(sockets) {
            stopping = true
            sockets.toList().forEach { socket -> runCatching { socket.close() } }
        }
    }

    /** 每次最多返回 32 条记录；短等待减少空轮询，并给其他慢接口留出执行机会。 */
    fun next(): JSONObject {
        lastRead = System.nanoTime()
        val batch = JSONArray()
        val first = events.poll(if (completion.isDone) 0 else 1000, TimeUnit.MILLISECONDS)
        if (first != null) batch.put(first)
        while (batch.length() < 32) batch.put(events.poll() ?: break)
        return JSONObject().put("events", batch).apply {
            if (completion.isDone && events.isEmpty()) put("summary", completion.get())
        }
    }

    private fun run() {
        try {
            val target = resolve() ?: return
            address = target.hostAddress
            if (stopping) return
            events.put(JSONObject().put("kind", "start").put("value", JSONObject()
                .put("host", options.host).put("address", address).put("total", options.ports.size)
                .put("concurrency", options.concurrency)))
            val finished = CountDownLatch(options.concurrency)
            repeat(options.concurrency) {
                Thread({
                    try { scan(target) }
                    catch (error: Exception) { fail(error) }
                    finally { finished.countDown() }
                }, "sview-port-probe").start()
            }
            finished.await()
        } catch (error: Exception) {
            if (!stopping) fail(error)
        } finally {
            stop()
            completion.complete(summary())
            // 唤醒正在等待结果的读取，关闭不必等到长轮询超时。
            events.offer(JSONObject().put("kind", "end"))
        }
    }

    /** 系统 DNS 可能不能立即中断；使用应用级有界线程池，取消后不再接受其结果。 */
    private fun resolve(): InetAddress? {
        if (stopping) return null
        dns.purge()
        val lookup = dns.submit<InetAddress> { InetAddress.getAllByName(options.host).first() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        try {
            while (!stopping) {
                try { return lookup.get(100, TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) {
                    if (System.nanoTime() >= deadline) throw ApiException("TIMEOUT", "解析主机地址超时")
                }
            }
            return null
        } finally { lookup.cancel(true) }
    }

    private fun scan(target: InetAddress) {
        while (!stopping) {
            val index = cursor.getAndIncrement()
            if (index >= options.ports.size) return
            if (!acquireSlot()) return
            val result = try { probe(target, options.ports[index]) } finally { connections.release() }
            if (result == null) return
            // 先取得队列空间再计入统计；主动关闭不把未交付的探测算成失败。
            synchronized(counts) {
                while (!stopping) {
                    if (events.remainingCapacity() == 0 && System.nanoTime() - lastRead > TimeUnit.SECONDS.toNanos(30)) {
                        fail(ApiException("CONNECTION_LOST", "网页长时间未读取扫描结果"))
                        break
                    }
                    if (events.offer(JSONObject().put("kind", "result").put("value", result), 100, TimeUnit.MILLISECONDS)) {
                        val state = result.getString("state")
                        counts[state] = counts.getValue(state) + 1
                        completed++
                        if (state == "open") openPorts.add(result.getInt("port"))
                        break
                    }
                }
            }
        }
    }

    private fun acquireSlot(): Boolean {
        while (!stopping) if (connections.tryAcquire(100, TimeUnit.MILLISECONDS)) return true
        return false
    }

    /** 只建立 TCP 连接，不发送业务数据，不推断应用协议。 */
    private fun probe(target: InetAddress, port: Int): JSONObject? {
        val socket = Socket()
        synchronized(sockets) {
            if (stopping) { socket.close(); return null }
            sockets.add(socket)
        }
        val begin = System.nanoTime()
        var state = "open"
        var error: Exception? = null
        try { socket.connect(InetSocketAddress(target, port), options.timeout) }
        catch (caught: SecurityException) { throw caught }
        catch (caught: Exception) { state = state(caught); error = caught }
        finally {
            runCatching { socket.close() }
            synchronized(sockets) { sockets.remove(socket) }
        }
        if (stopping) return null
        return JSONObject().put("address", address).put("port", port).put("protocol", "tcp")
            .put("state", state).put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).apply {
                if (state == "error" && error != null) {
                    put("code", ApiErrors.code(error)); put("message", "端口连接失败")
                }
            }
    }

    /** Android 的底层 errno 比 ConnectException 文案可靠，不能把所有连接失败当成拒绝。 */
    private fun state(error: Exception): String {
        if (error is SocketTimeoutException) return "timeout"
        if (error is NoRouteToHostException) return "unreachable"
        val errno = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<ErrnoException>().firstOrNull()?.errno
        return when (errno) {
            OsConstants.ECONNREFUSED -> "closed"
            OsConstants.ETIMEDOUT -> "timeout"
            OsConstants.ENETUNREACH, OsConstants.EHOSTUNREACH -> "unreachable"
            else -> "error"
        }
    }

    private fun fail(error: Exception) {
        synchronized(sockets) {
            if (stopping) return
            failure = JSONObject().put("code", ApiErrors.code(error)).put("message", when (ApiErrors.cause(error)) {
                is java.net.UnknownHostException -> "无法解析主机地址"
                is SecurityException -> "系统不允许建立网络连接"
                else -> ApiErrors.cause(error).message ?: "扫描失败"
            })
            stop()
        }
    }

    private fun summary(): JSONObject = synchronized(counts) {
        JSONObject().put("host", options.host).put("address", address ?: JSONObject.NULL).put("protocol", "tcp")
            .put("reason", if (failure != null) "error" else if (completed == options.ports.size) "completed" else "closed")
            .put("total", options.ports.size).put("completed", completed).put("openPorts", JSONArray(openPorts.toList()))
            .put("counts", JSONObject(counts.toMap())).put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
            .apply { failure?.let { put("error", it) } }
    }

    companion object {
        // 所有项目共享连接预算；DNS 线程空闲后回收，不让无法中断的系统解析无限累积。
        private val connections = Semaphore(32, true)
        private val dns = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4)).apply {
            allowCoreThreadTimeOut(true)
        }
    }
}
