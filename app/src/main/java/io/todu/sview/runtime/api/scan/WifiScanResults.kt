package io.todu.sview.runtime.api.scan

import org.json.JSONArray
import org.json.JSONObject

/** 只保留请求开始后观察到的热点；同名不同 BSSID 的热点保持独立。 */
internal object WifiScanResults {
    data class Entry(val ssid: String, val bssid: String, val rssi: Int, val frequency: Int, val capabilities: String, val seenAt: Long)

    fun toJson(entries: List<Entry>, started: Long): JSONArray = JSONArray().apply {
        entries.filter { it.seenAt >= started && it.bssid.isNotBlank() }
            .groupBy { it.bssid.uppercase() }.values.map { group -> group.maxBy { it.rssi } }
            .sortedWith(compareByDescending<Entry> { it.rssi }.thenBy { it.bssid }).forEach {
                put(JSONObject().put("ssid", it.ssid).put("bssid", it.bssid).put("rssi", it.rssi)
                    .put("frequency", it.frequency).put("capabilities", it.capabilities))
            }
    }
}
