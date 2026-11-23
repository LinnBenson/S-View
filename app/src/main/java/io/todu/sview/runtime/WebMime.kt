package io.todu.sview.runtime

import java.io.File
import java.net.URLConnection

/** 项目预览和局域网静态文件共用类型映射。 */
internal object WebMime {
    fun of(file: File): String = when (file.extension.lowercase()) {
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "html", "htm" -> "text/html; charset=utf-8"
        "svg" -> "image/svg+xml"
        "json", "map" -> "application/json; charset=utf-8"
        "wasm" -> "application/wasm"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
    }
}
