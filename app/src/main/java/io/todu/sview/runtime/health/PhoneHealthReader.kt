package io.todu.sview.runtime.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

/** 手机只查询 Health Connect 已保存的记录，日汇总交由系统去重。 */
internal object PhoneHealthReader {
    val types = linkedMapOf("steps" to StepsRecord::class, "distance" to DistanceRecord::class,
        "calories" to TotalCaloriesBurnedRecord::class, "heartRate" to HeartRateRecord::class,
        "oxygenSaturation" to OxygenSaturationRecord::class, "sleep" to SleepSessionRecord::class)
    val permissions: Set<String> = types.values.map { HealthPermission.getReadPermission(it) }.toSet()

    suspend fun read(context: Context, result: HealthSnapshot) {
        types.keys.forEach { result.state(it, "unavailable") }
        val availability = HealthConnectClient.getSdkStatus(context)
        if (availability != HealthConnectClient.SDK_AVAILABLE) {
            result.providerStatus = if (availability == HealthConnectClient.SDK_UNAVAILABLE) "unsupported" else "update_required"
            types.keys.forEach { result.state(it, if (availability == HealthConnectClient.SDK_UNAVAILABLE) "unsupported" else "unavailable") }
            return
        }
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        result.providerStatus = "available"
        types.forEach { (name, type) ->
            result.state(name, if (HealthPermission.getReadPermission(type) in granted) "unavailable" else "permission_denied")
        }
        for ((name, type) in types) {
            if (HealthPermission.getReadPermission(type) !in granted) continue
            try {
                when (name) {
                    "steps" -> daily(client, result, name, StepsRecord.COUNT_TOTAL) { it }
                    "distance" -> daily(client, result, name, DistanceRecord.DISTANCE_TOTAL) { it.inMeters }
                    "calories" -> daily(client, result, name, TotalCaloriesBurnedRecord.ENERGY_TOTAL) { it.inKilocalories }
                    "heartRate" -> heartRate(client, result)
                    "oxygenSaturation" -> {
                        val record = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class,
                            TimeRangeFilter.between(result.historyStart, result.now), ascendingOrder = false, pageSize = 1)).records.firstOrNull()
                        result.value(name, record?.percentage?.value, record?.time, record?.time)
                    }
                    "sleep" -> sleep(client, result)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { result.state(name, "permission_denied") }
            catch (_: Exception) { result.state(name, "unavailable") }
        }
    }

    private suspend fun <T : Any> daily(client: HealthConnectClient, result: HealthSnapshot, name: String,
        metric: AggregateMetric<T>, number: (T) -> Number) {
        val data = client.aggregate(AggregateRequest(setOf(metric), TimeRangeFilter.between(result.dayStart, result.now)))
        result.value(name, data[metric]?.let(number), result.dayStart, result.now)
    }

    /** 按最近一天向前查找，页内逐个检查采样时间，不能把记录开始时间当成最新心率时间。 */
    private suspend fun heartRate(client: HealthConnectClient, result: HealthSnapshot) {
        var end = result.now
        while (end > result.historyStart) {
            val start = maxOf(result.historyStart, end.minus(1, ChronoUnit.DAYS))
            var latest: HeartRateRecord.Sample? = null
            pages(client, HeartRateRecord::class, start, end) { records ->
                records.forEach { record -> record.samples.forEach { sample ->
                    if (sample.time >= start && sample.time < end && (latest == null || sample.time > latest!!.time)) latest = sample
                } }
            }
            latest?.let { result.value("heartRate", it.beatsPerMinute, it.time, it.time); return }
            end = start
        }
        result.value("heartRate", null, null, null)
    }

    private suspend fun sleep(client: HealthConnectClient, result: HealthSnapshot) {
        var latest: SleepSessionRecord? = null
        pages(client, SleepSessionRecord::class, result.historyStart, result.now) { records ->
            records.forEach { record ->
                if (record.endTime <= result.now && record.endTime >= result.historyStart &&
                    (latest == null || record.endTime > latest!!.endTime)) latest = record
            }
        }
        val record = latest
        // 返回最近一条睡眠记录的完整起止时长，不将它描述为排除清醒阶段的净睡眠。
        result.value("sleep", record?.let { Duration.between(it.startTime, it.endTime).toMillis() / 60000.0 },
            record?.startTime, record?.endTime)
    }

    private suspend fun <T : Record> pages(client: HealthConnectClient, type: KClass<T>, start: Instant, end: Instant,
        consume: (List<T>) -> Unit) {
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(start, end),
                ascendingOrder = false, pageSize = 200, pageToken = token))
            consume(page.records)
            token = page.pageToken?.takeIf { it.isNotEmpty() }
        } while (token != null)
    }
}
