package io.todu.sview.storage

import android.webkit.MimeTypeMap
import java.io.File
import java.net.URLConnection

/** 使用系统类型表识别 WebP 等格式，图片展示与文件打开采用相同规则。 */
internal object DeviceResourceMime {
    fun type(file: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
}
