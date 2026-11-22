package io.todu.sview.runtime.api

import io.todu.sview.data.WindowDimension
import org.json.JSONObject

/** 解析窗口操作；尺寸默认临时生效，persist 为 true 时保存配置。 */
internal sealed interface InteractionApiRequest {
    data class Margin(val margins: io.todu.sview.data.WindowMargins, val persist: Boolean) : InteractionApiRequest
    data class CornerRadius(val cornerRadius: Double, val persist: Boolean) : InteractionApiRequest
    data class Preview(val path: String) : InteractionApiRequest
    data object RemoveClose : InteractionApiRequest
    data class Location(val location: io.todu.sview.data.WindowLocation, val persist: Boolean) : InteractionApiRequest
    data object Close : InteractionApiRequest
    data object CloseThis : InteractionApiRequest
    data class Resize(val width: WindowDimension, val height: WindowDimension, val persist: Boolean) : InteractionApiRequest

    companion object {
        fun parse(json: JSONObject): InteractionApiRequest {
            val args = json.getJSONObject("args")
            return when (json.getString("method")) {
                "interaction.preview" -> {
                    val path = args.get("path")
                    require(path is String && path.isNotBlank() && path.length <= 4096) { "path 应为非空图片路径" }
                    require(!path.contains(":") || path.startsWith("device:/")) { "仅支持项目路径和 device:/ 路径" }
                    Preview(path)
                }
                "interaction.margin" -> {
                    val persist = args.opt("persist") ?: false
                    require(persist is Boolean) { "persist 应为 boolean" }
                    Margin(io.todu.sview.data.WindowMargins.fromJson(args.getJSONObject("margins")), persist)
                }
                "interaction.cornerRadius" -> {
                    val persist = args.opt("persist") ?: false
                    require(persist is Boolean) { "persist 应为 boolean" }
                    val value = args.get("cornerRadius")
                    require(value is Number) { "cornerRadius 应为 number" }
                    val radius = value.toDouble()
                    require(radius.isFinite() && radius >= 0.0 && radius <= Float.MAX_VALUE) { "圆角应为 0 到 3.4028234663852886e38 之间的有限数值，单位为 dp" }
                    CornerRadius(radius, persist)
                }
                "interaction.closeThis" -> CloseThis
                "interaction.close" -> Close
                "interaction.removeClose" -> RemoveClose
                "interaction.location" -> {
                    val persist = args.opt("persist") ?: false
                    require(persist is Boolean) { "persist 应为 boolean" }
                    val value = args.get("location")
                    require(value is String) { "location 应为 string" }
                    Location(io.todu.sview.data.WindowLocation.parse(value), persist)
                }
                "interaction.resize" -> {
                    val persist = if (args.has("persist")) args.get("persist") else false
                    require(persist is Boolean) { "persist 应为 boolean" }
                    Resize(dimension(args, "width"), dimension(args, "height"), persist)
                }
                else -> throw IllegalArgumentException("接口不存在")
            }
        }

        private fun dimension(args: JSONObject, key: String): WindowDimension {
            val value = args.get(key)
            require(value is String && value.length <= 64) { "$key 应为带 dp、px 或 % 单位的尺寸" }
            return WindowDimension.parse(value)
        }
    }
}
