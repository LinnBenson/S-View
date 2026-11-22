package io.todu.sview.storage

import io.todu.sview.runtime.storage.StoragePaths
import java.io.File
import java.util.zip.ZipFile

/** 为系统安装器准备独立副本，仅共享这一份 APK，不暴露项目或设备目录。 */
internal class ApkInstallFiles(projectRoot: File, deviceRoot: () -> File, private val directory: File) {
    private val paths = StoragePaths(projectRoot, deviceRoot)

    /** 分块复制可随窗口关闭而取消；失败只删除本次未交付的副本。 */
    fun prepare(path: String, ensureActive: () -> Unit): File {
        ensureActive()
        val source = paths.resolve(path).file
        require(source.isFile && source.canRead()) { "安装包不存在或无法读取" }
        require(source.extension.equals("apk", ignoreCase = true)) { "请选择 APK 安装包" }
        require(source.length() > 0) { "安装包为空" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建安装临时目录" }
        cleanup()
        val target = File.createTempFile("install-", ".apk", directory)
        try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                }
            }
            ensureActive()
            val valid = runCatching { ZipFile(target).use { it.getEntry("AndroidManifest.xml")?.isDirectory == false } }.getOrDefault(false)
            require(valid) { "文件不是有效的 APK 安装包" }
            return target
        } catch (error: Exception) { target.delete(); throw error }
    }

    /** 已交给系统的副本不随运行窗口销毁；下次安装时回收超过一天的副本。 */
    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        directory.listFiles()?.filter { it.isFile && it.name.startsWith("install-") && it.extension == "apk" && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }
}
