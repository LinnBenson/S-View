package io.todu.sview.runtime.api.scan

import org.json.JSONArray
import org.json.JSONObject

/** 经典发现和 BLE 广播按地址合并，未知名称不会覆盖已知名称。 */
internal class BluetoothScanResults {
    private data class Entry(val address: String, var name: String?, var rssi: Int?, var type: String, var paired: Boolean, val sources: MutableSet<String>)
    private val entries = linkedMapOf<String, Entry>()

    fun add(address: String, name: String?, rssi: Int?, type: String, paired: Boolean, source: String) {
        val key = address.uppercase()
        val entry = entries.getOrPut(key) { Entry(key, null, null, type, paired, linkedSetOf()) }
        if (!name.isNullOrBlank()) entry.name = name
        if (rssi != null) entry.rssi = rssi
        entry.paired = paired
        entry.sources.add(source)
        if (type == "dual" || entry.type == "dual") entry.type = "dual" else if (type != "unknown") entry.type = type
    }

    fun toJson() = JSONArray().apply {
        entries.values.sortedWith(compareByDescending<Entry> { it.rssi ?: Int.MIN_VALUE }.thenBy { it.address }).forEach {
            put(JSONObject().put("address", it.address).put("name", it.name ?: JSONObject.NULL)
                .put("rssi", it.rssi ?: JSONObject.NULL).put("type", it.type).put("paired", it.paired)
                .put("sources", JSONArray(it.sources.toList())))
        }
    }
}
