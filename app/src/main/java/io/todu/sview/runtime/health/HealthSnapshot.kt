package io.todu.sview.runtime.health

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 两端共用字段与单位，缺失值始终为 null，真实的零值仍标记为 available。 */
internal class HealthSnapshot(val source: String) {
    val now: Instant = Instant.now()
    val zone: ZoneId = ZoneId.systemDefault()
    val dayStart: Instant = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
    val historyStart: Instant = now.minus(30, ChronoUnit.DAYS)
    var providerStatus = "unavailable"
    private val units = linkedMapOf("steps" to "count", "distance" to "m", "calories" to "kcal",
        "heartRate" to "bpm", "oxygenSaturation" to "%", "sleep" to "min")
    private val values = units.mapValues { (_, unit) -> metric(unit, "unsupported", null, null, null) }.toMutableMap()

    @Synchronized fun state(name: String, status: String) {
        values[name] = metric(units.getValue(name), status, null, null, null)
    }

    @Synchronized fun value(name: String, value: Number?, start: Instant?, end: Instant?) {
        values[name] = metric(units.getValue(name), if (value == null) "no_data" else "available", value,
            if (value == null) null else start, if (value == null) null else end)
    }

    @Synchronized fun endTime(name: String): Long = values.getValue(name).optLong("endTime", -1)

    @Synchronized fun json(): JSONObject = JSONObject().put("source", source).put("providerStatus", providerStatus)
        .put("timestamp", Instant.now().toEpochMilli()).put("timeZone", zone.id)
        .put("dayStart", dayStart.toEpochMilli()).put("historyStart", historyStart.toEpochMilli())
        .apply { values.forEach { (key, value) -> put(key, value) } }

    private fun metric(unit: String, status: String, value: Number?, start: Instant?, end: Instant?) = JSONObject()
        .put("value", value ?: JSONObject.NULL).put("unit", unit).put("status", status)
        .put("startTime", start?.toEpochMilli() ?: JSONObject.NULL).put("endTime", end?.toEpochMilli() ?: JSONObject.NULL)
}
