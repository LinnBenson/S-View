package io.todu.sview.runtime.api

import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** HTTP 工作线程等待主线程结果，网页线程保持异步；关闭时取消尚未执行的请求。 */
internal class MainThreadCalls : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)
    private val pending = ConcurrentHashMap.newKeySet<FutureTask<Any>>()

    fun invoke(action: () -> Any): Any {
        check(active.get()) { "运行窗口已关闭" }
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask { check(active.get()) { "运行窗口已关闭" }; action() }
        pending.add(task)
        handler.post(task)
        return try {
            task.get(5, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw (error.cause as? Exception ?: error)
        } catch (_: TimeoutException) {
            throw IllegalStateException("系统接口响应超时")
        } finally {
            task.cancel(false)
            handler.removeCallbacks(task)
            pending.remove(task)
        }
    }

    override fun close() {
        active.set(false)
        pending.forEach { it.cancel(false) }
        pending.clear()
        handler.removeCallbacksAndMessages(null)
    }
}
