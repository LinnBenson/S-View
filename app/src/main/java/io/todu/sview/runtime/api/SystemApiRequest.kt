package io.todu.sview.runtime.api

import org.json.JSONObject
import java.net.URI

/** 请求解析后使用明确类型分发，不能从脚本透传任意 Intent 或系统调用。 */
sealed interface SystemApiRequest {
    data class Toast(val message: String, val duration: String) : SystemApiRequest
    data class Notify(val title: String, val message: String) : SystemApiRequest
    data class Vibrate(val duration: Long) : SystemApiRequest
    data class Install(val path: String) : SystemApiRequest
    data class OpenApp(val packageName: String) : SystemApiRequest
    data class OpenLink(val url: String) : SystemApiRequest
    data object ReadClipboard : SystemApiRequest
    data class WriteClipboard(val text: String) : SystemApiRequest
    data class Share(val text: String, val title: String) : SystemApiRequest
    data object RotationSettings : SystemApiRequest
    data object CancelVibration : SystemApiRequest
    data object Info : SystemApiRequest
    data object Screen : SystemApiRequest
    data object NetworkState : SystemApiRequest
    data object WifiState : SystemApiRequest
    data object BluetoothState : SystemApiRequest
    data object HealthInfo : SystemApiRequest
    data object BatteryInfo : SystemApiRequest
    data class ScanWifi(val timeout: Long) : SystemApiRequest
    data class ScanBluetooth(val timeout: Long) : SystemApiRequest
    data class Location(val timeout: Long) : SystemApiRequest

    companion object {
        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        fun parse(json: JSONObject): SystemApiRequest {
            val args = json.getJSONObject("args")
            return when (json.getString("method")) {
                "system.toast" -> {
                    val duration = if (args.has("duration")) text(args, "duration", 5) else "short"
                    require(duration in setOf("short", "long")) { "duration 应为 short 或 long" }
                    Toast(text(args, "message", 2000), duration)
                }
                "system.notify" -> Notify(text(args, "title", 120), text(args, "message", 2000))
                "system.vibrate" -> Vibrate(integer(args, "duration", 50, 1, 10000))
                "system.install" -> Install(text(args, "path", 4096))
                "system.openApp" -> OpenApp(text(args, "packageName", 255).also {
                    require(packagePattern.matches(it)) { "请输入有效的应用包名" }
                })
                "system.openLink" -> OpenLink(link(text(args, "url", 4096)))
                "system.readClipboard" -> ReadClipboard
                "system.writeClipboard" -> WriteClipboard(text(args, "text", 8192, allowEmpty = true))
                "system.share" -> Share(text(args, "text", 8192), if (args.has("title")) text(args, "title", 120, allowEmpty = true) else "")
                "system.getRotationSettings" -> RotationSettings
                "system.cancelVibration" -> CancelVibration
                "system.getInfo" -> Info
                "system.getScreen" -> Screen
                "system.getNetworkState" -> NetworkState
                "system.scanWifi" -> ScanWifi(integer(args, "timeout", 15000, 1000, 30000))
                "system.scanBluetooth" -> ScanBluetooth(integer(args, "timeout", 12000, 1000, 30000))
                "system.getWifiState" -> WifiState
                "system.getBluetoothState" -> BluetoothState
                "system.getHealthInfo" -> HealthInfo
                "system.getBatteryInfo" -> BatteryInfo
                "system.getLocation" -> Location(integer(args, "timeout", 15000, 1000, 30000))
                else -> throw IllegalArgumentException("接口不存在")
            }
        }

        private fun integer(args: JSONObject, key: String, default: Long, min: Long, max: Long): Long {
            if (!args.has(key)) return default
            val value = args.get(key)
            require(value is Number) { "$key 应为毫秒数" }
            val number = value.toDouble()
            require(number.isFinite() && number >= min && number <= max && number % 1.0 == 0.0) { "$key 应为 $min–$max 的整数" }
            return number.toLong()
        }

        private fun link(value: String): String {
            val uri = runCatching { URI(value) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            require(uri != null && scheme != null && uri.rawSchemeSpecificPart.isNotBlank() &&
                scheme !in setOf("javascript", "data", "file", "content", "intent")) { "请使用网页、地图或应用链接" }
            if (scheme in setOf("http", "https")) require(!uri.host.isNullOrBlank()) { "网页地址无效" }
            return value
        }

        private fun text(json: JSONObject, key: String, limit: Int, allowEmpty: Boolean = false): String {
            val value = json.get(key)
            require(value is String && value.length <= limit && (allowEmpty || value.isNotBlank())) {
                "$key 应为${if (allowEmpty) "不超过" else " 1 至"} $limit 个字符的文字"
            }
            return value
        }
    }
}
