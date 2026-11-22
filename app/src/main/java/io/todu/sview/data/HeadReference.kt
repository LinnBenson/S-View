package io.todu.sview.data

import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** 项目独立保存引用顺序；加载方式使用浏览器原生语义。 */
data class HeadReference(
    val id: String = UUID.randomUUID().toString(),
    val source: String,
    val type: String = "js",
    val mode: String = "default"
) {
    fun validate() {
        require(source.isNotBlank()) { "请输入引用地址" }
        val uri = runCatching { URI(source) }.getOrNull()
        require(uri != null && (uri.scheme == null || uri.scheme.lowercase() in listOf("https", "http"))) { "请输入项目路径或 HTTP/HTTPS 地址" }
        require(type in listOf("js", "css") && mode in modes(type).keys) { "请选择有效的加载方式" }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("source", source).put("type", type).put("mode", mode)

    /** 地址只写入属性，并转义引号，不能将输入拼成 HTML 标签。 */
    fun toHtml(): String {
        validate()
        val url = source.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        return if (type == "css") {
            val loading = if (mode == "nonblocking") " media=\"print\" onload=\"this.media='all'\"" else ""
            "<link rel=\"stylesheet\" href=\"$url\"$loading>"
        } else {
            val loading = when (mode) { "defer" -> " defer"; "async" -> " async"; "module" -> " type=\"module\""; else -> "" }
            "<script src=\"$url\"$loading></script>"
        }
    }

    companion object {
        fun modes(type: String): Map<String, String> = if (type == "css") linkedMapOf("default" to "默认", "nonblocking" to "非阻塞")
            else linkedMapOf("default" to "同步", "defer" to "延后", "async" to "异步", "module" to "模块")

        fun fromJson(json: JSONObject) = HeadReference(json.getString("id"),
            json.getString("source"), json.getString("type"), json.getString("mode"))
    }
}
