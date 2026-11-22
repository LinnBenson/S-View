package io.todu.sview.runtime.health

import android.content.Context
import android.os.Build
import android.os.SystemClock
import io.todu.sview.wear.WatchDevice
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.Closeable
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** 同一窗口合并并发读取，五秒内复用快照；后台、权限或日期变化后立即失效。 */
internal class HealthInfoReader(private val context: Context) : Closeable {
    private class Request(val key: String) {
        val job = Job()
        val result = CompletableFuture<String>()
    }
    private var active: Request? = null
    private var closed = false
    private var cached: String? = null
    private var cacheKey = ""
    private var cachedAt = 0L

    fun read(): JSONObject {
        val key = snapshotKey()
        var owner = false
        val request = synchronized(this) {
            check(!closed) { "运行窗口已关闭" }
            cached?.takeIf { cacheKey == key && SystemClock.elapsedRealtime() - cachedAt < 5000 }?.let { return JSONObject(it) }
            active?.takeIf { it.key == key } ?: run {
                active?.job?.cancel()
                Request(key).also { active = it; owner = true }
            }
        }
        if (owner) {
            try {
                val value = readSnapshot(request.job).toString()
                synchronized(this) {
                    if (!closed && active === request && request.job.isActive) {
                        cached = value; cacheKey = key; cachedAt = SystemClock.elapsedRealtime()
                    }
                }
                request.result.complete(value)
            } catch (error: Exception) { request.result.completeExceptionally(error) }
            finally {
                synchronized(this) { if (active === request) active = null }
                request.job.complete()
            }
        }
        return try { JSONObject(request.result.get()) }
        catch (error: ExecutionException) { throw (error.cause as? Exception ?: error) }
    }

    /** 不改写缓存中的采样时间和查询时间，调用方能判断数据的新鲜程度。 */
    private fun readSnapshot(job: CompletableJob): JSONObject {
        val watch = WatchDevice.isWatch(context)
        val result = HealthSnapshot(if (watch) "health_services" else "health_connect")
        runBlocking(Dispatchers.IO + job) {
            withTimeoutOrNull(15000) {
                try {
                    if (watch) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            listOf("steps", "distance", "calories", "heartRate").forEach { result.state(it, "unavailable") }
                            WatchHealthReader.read(context, result)
                        } else result.providerStatus = "unsupported"
                    } else PhoneHealthReader.read(context, result)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { result.providerStatus = "unavailable" }
            }
        }
        return result.json()
    }

    private fun snapshotKey(): String {
        val permissions = if (WatchDevice.isWatch(context) && Build.VERSION.SDK_INT >= 30)
            WatchHealthReader.permissions().toList() else PhoneHealthReader.permissions.toList()
        return "${ZoneId.systemDefault()}:${LocalDate.now()}:" + permissions.joinToString { "$it=${context.checkSelfPermission(it)}" }
    }

    @Synchronized fun cancel() {
        cached = null
        active?.let {
            it.result.completeExceptionally(CancellationException("健康数据读取已取消"))
            it.job.cancel()
        }
        active = null
    }
    @Synchronized override fun close() { closed = true; cancel() }
}
