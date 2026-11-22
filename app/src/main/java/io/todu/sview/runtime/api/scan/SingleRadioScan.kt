package io.todu.sview.runtime.api.scan

import android.os.Handler
import android.os.Looper
import io.todu.sview.runtime.cleanupResource
import org.json.JSONArray
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 一次前台扫描的生命周期；所有回调与清理在主线程串行执行。 */
internal class SingleRadioScan(private val kind: String, private val foreground: () -> Boolean) : Closeable {
    internal class Session {
        val result = CompletableFuture<JSONArray>()
        val cleanup = mutableListOf<() -> Unit>()
        var timeout: () -> JSONArray = { error("扫描超时，请重试") }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)
    private val pending = AtomicReference<Session?>(null)
    companion object {
        // 经典蓝牙 discovery 属于进程共享资源，不能由另一运行窗口取消。
        private val owners = ConcurrentHashMap<String, Session>()
    }

    fun read(timeout: Long, start: (Session, (Result<JSONArray>) -> Unit) -> Unit): JSONArray {
        check(active.get()) { "运行窗口已关闭" }
        val session = Session()
        check(pending.compareAndSet(null, session)) { "正在扫描，请等待本次扫描结束" }
        if (owners.putIfAbsent(kind, session) != null) {
            pending.compareAndSet(session, null)
            error("已有窗口正在扫描，请稍后重试")
        }
        val deadline = Runnable { finish(session, runCatching { session.timeout() }) }
        handler.post {
            try {
                check(active.get() && pending.get() === session && !session.result.isDone) { "扫描已取消" }
                check(foreground()) { "请在运行窗口位于前台时扫描" }
                session.cleanup.add { handler.removeCallbacks(deadline) }
                handler.postDelayed(deadline, timeout)
                start(session) { result -> onMain { finish(session, result) } }
            } catch (error: Exception) { finish(session, Result.failure(error)) }
        }
        return try { session.result.get(timeout + 5000, TimeUnit.MILLISECONDS) }
        catch (error: ExecutionException) { throw (error.cause as? Exception ?: error) }
        catch (_: java.util.concurrent.TimeoutException) { error("扫描响应超时") }
        finally { onMain { finish(session, Result.failure(IllegalStateException("扫描已取消"))) } }
    }

    private fun finish(session: Session, result: Result<JSONArray>) {
        if (session.result.isDone) return
        session.cleanup.asReversed().forEach { cleanupResource("$kind 扫描") { it() } }
        session.cleanup.clear()
        pending.compareAndSet(session, null)
        owners.remove(kind, session)
        result.fold(session.result::complete, session.result::completeExceptionally)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    fun cancel() { pending.get()?.let { session -> onMain { finish(session, Result.failure(IllegalStateException("扫描已取消"))) } } }
    override fun close() { active.set(false); cancel() }
}
