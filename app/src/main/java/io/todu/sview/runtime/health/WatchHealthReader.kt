package io.todu.sview.runtime.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 仅短时接收系统被动数据，不调用测量或运动接口，不注册后台服务。 */
@RequiresApi(30)
internal object WatchHealthReader {
    private val lock = Mutex()
    private var clearing: ListenableFuture<Void>? = null
    private val daily = linkedMapOf("steps" to DataType.STEPS_DAILY, "distance" to DataType.DISTANCE_DAILY,
        "calories" to DataType.CALORIES_DAILY)

    fun permissions(): Array<String> = arrayOf(Manifest.permission.ACTIVITY_RECOGNITION,
        if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS)

    suspend fun read(context: Context, result: HealthSnapshot) = lock.withLock {
        val client = HealthServices.getClient(context).passiveMonitoringClient
        val candidates = daily + ("heartRate" to DataType.HEART_RATE_BPM)
        candidates.keys.forEach { result.state(it, "unavailable") }
        // 等上一次注销完成后再注册，避免迟到的注销操作删掉新请求的回调。
        clearing?.awaitHealth()
        clearing = null
        val supported = client.getCapabilitiesAsync().awaitHealth().supportedDataTypesPassiveMonitoring
        result.providerStatus = "available"
        val permissions = permissions()
        val selected = candidates.filter { (name, type) ->
            val permission = if (name == "heartRate") permissions[1] else permissions[0]
            val allowed = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            result.state(name, when { type !in supported -> "unsupported"; !allowed -> "permission_denied"; else -> "no_data" })
            type in supported && allowed
        }
        if (selected.isEmpty()) return@withLock
        val registered = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        val received = mutableSetOf<String>()
        val accepting = AtomicBoolean(true)
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val callback = object : PassiveListenerCallback {
            override fun onRegistered() { registered.complete(Unit) }
            override fun onRegistrationFailed(throwable: Throwable) { registered.completeExceptionally(throwable) }
            override fun onPermissionLost() {
                synchronized(received) {
                    accepting.set(false)
                    selected.keys.forEach { result.state(it, "permission_denied") }
                    complete.complete(Unit)
                }
            }
            override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
                synchronized(received) {
                    if (!accepting.get()) return
                    dataPoints.intervalDataPoints.forEach { point ->
                        val name = selected.entries.find { it.value == point.dataType }?.key ?: return@forEach
                        val end = point.getEndInstant(boot)
                        val value = point.value as? Number ?: return@forEach
                        // 日累计取最新值而不是累加批次，排除系统尚未重置的昨日数据。
                        if (end >= result.dayStart && end.atZone(result.zone).toLocalDate() == result.now.atZone(result.zone).toLocalDate()) {
                            val existing = result.endTime(name)
                            if (end.toEpochMilli() >= existing && value.toDouble().isFinite() && value.toDouble() >= 0) {
                                result.value(name, value, result.dayStart, end)
                                received.add(name)
                            }
                        }
                    }
                    if ("heartRate" in selected) dataPoints.getData(DataType.HEART_RATE_BPM).forEach { point ->
                        val time = point.getTimeInstant(boot)
                        val existing = result.endTime("heartRate")
                        if (time >= result.historyStart && time.toEpochMilli() >= existing && point.value.isFinite() && point.value > 0) {
                            result.value("heartRate", point.value, time, time)
                            received.add("heartRate")
                        }
                    }
                    if (received.containsAll(selected.keys)) complete.complete(Unit)
                }
            }
        }
        try {
            client.setPassiveListenerCallback(PassiveListenerConfig.builder().setDataTypes(selected.values.toSet()).build(), callback)
            registered.await()
            client.flushAsync().awaitHealth()
            // 没有新批次时正常返回 no_data，不用一次传感器测量冒充已有记录。
            withTimeoutOrNull(5000) { complete.await() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            synchronized(received) { selected.keys.filter { it !in received }.forEach {
                result.state(it, if (error is SecurityException) "permission_denied" else "unavailable")
            } }
        } finally {
            accepting.set(false)
            clearing = client.clearPassiveListenerCallbackAsync()
            withContext(NonCancellable) { withTimeoutOrNull(2000) { clearing?.awaitHealth() } }
        }
    }

    /** 取消等待不取消已发出的注销请求，它必须继续执行以释放系统监听。 */
    private suspend fun <T> ListenableFuture<T>.awaitHealth(): T = suspendCancellableCoroutine { continuation ->
        addListener({
            if (continuation.isActive) try { continuation.resume(get()) }
            catch (error: ExecutionException) { continuation.resumeWithException(error.cause ?: error) }
            catch (error: Exception) { continuation.resumeWithException(error) }
        }, { it.run() })
    }
}
