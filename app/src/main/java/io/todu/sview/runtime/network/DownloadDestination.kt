package io.todu.sview.runtime.network

import okhttp3.Response
import java.io.File
import java.net.URLDecoder

/** 统一解析项目文件和 device:/ 路径，目录下载在收到响应头后确定文件名。 */
internal class DownloadDestination(private val root: File, private val path: String, val device: Boolean) {
    private val relative = if (device) path.removePrefix("device:/") else path.removePrefix("/")
    private val base = scoped(relative)
    private val directory = device && (relative.isEmpty() || relative.endsWith('/') ||
        !relative.contains('/') || base.isDirectory)
    var resultPath: String = path
        private set

    init {
        require(relative.split('/').none { it == ".." || it == "." } && '\\' !in relative &&
            relative.none { it.code < 32 || it.code == 127 }) { "下载路径无效" }
        require(directory || !base.exists()) { "下载目标已存在" }
        require(!directory || !base.exists() || base.isDirectory) { "下载目录不是文件夹" }
    }

    /** 文件名只取单个名称，不允许响应头改变调用者选择的目录。 */
    fun resolve(response: Response): File {
        val target = if (directory) scoped(relative.trimEnd('/') + "/" + responseFileName(response)) else base
        require(!target.exists()) { "下载目标已存在" }
        if (device) resultPath = "device:/" + target.relativeTo(root.canonicalFile).invariantSeparatorsPath
        return target
    }

    private fun scoped(value: String): File {
        val parent = root.canonicalFile
        val target = File(parent, value.removePrefix("/")).canonicalFile
        require(target == parent || target.path.startsWith(parent.path + File.separator)) { "下载路径超出存储目录" }
        return target
    }

    private fun responseFileName(response: Response): String {
        val disposition = response.header("Content-Disposition").orEmpty()
        val encoded = Regex("(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.get(1)?.trim()?.trim('"')
        val extended = encoded?.split('\'', limit = 3)?.takeIf { it.size == 3 && it[0].equals("UTF-8", true) }
            ?.let { runCatching { URLDecoder.decode(it[2].replace("+", "%2B"), "UTF-8") }.getOrNull() }
        val plain = Regex("(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", RegexOption.IGNORE_CASE)
            .find(disposition)?.let { it.groupValues[1].ifEmpty { it.groupValues[2].trim() } }
        return sequenceOf(extended, plain, response.request.url.pathSegments.lastOrNull(), "download")
            .filterNotNull().first { validFileName(it) }
    }

    private fun validFileName(name: String) = name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 } && name.toByteArray().size <= 240
}
