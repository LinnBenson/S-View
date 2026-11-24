package io.todu.sview.data

import java.io.File
import java.security.MessageDigest

/** 生成文件与编辑源文件分离，项目删除时随私有根目录一起清理。 */
class RuntimeArtifactStore(
    private val projectDirectory: (String) -> File,
    private val resolveSource: (String, String) -> File,
    private val write: (File, String) -> Unit,
    private val isWatch: Boolean
) {
    private fun directory(id: String) = File(projectDirectory(id), "runtime")

    /** 只移除可重新生成的产物，不接触项目源文件。 */
    fun clear(id: String) {
        val folder = directory(id)
        check(!folder.exists() || folder.deleteRecursively()) { "无法清除运行缓存" }
    }

    fun resolve(id: String, path: String): File? = when (path.removePrefix("/")) {
        "index.html", "main.js" -> File(directory(id), path.removePrefix("/"))
        else -> null
    }

    /** 两份产物独立判断；强制刷新用于缓存恢复，保存通过 changedPath 指明变化文件。 */
    @JvmOverloads
    fun update(project: Project, force: Boolean = false, changedPath: String? = null) {
        val entry = resolveSource(project.id, "index.html")
        val script = resolveSource(project.id, "main.js")
        val folder = directory(project.id)
        val index = File(folder, "index.html")
        val main = File(folder, "main.js")
        val manifest = File(folder, "signature")
        val previous = if (manifest.isFile) manifest.readLines() else emptyList()
        val indexSignature = hash(buildString {
            append(CACHE_VERSION).append(isWatch).append(fileStamp(entry))
            project.variables.forEach { append(it.name.length).append(':').append(it.name).append(':').append(it.type).append(':').append(it.value.length).append(':').append(it.value).append('\n') }
            project.references.forEach { append(it.toJson()).append('\n') }
        })
        val scriptSignature = hash(fileStamp(script))
        val updateIndex = force || changedPath == "index.html" || !index.isFile || previous.getOrNull(0) != indexSignature
        val updateScript = force || changedPath == "main.js" || !main.isFile || previous.getOrNull(1) != scriptSignature
        if (!updateIndex && !updateScript) return
        if (updateIndex) {
            require(entry.isFile) { "缺少入口模板 index.html" }
            write(index, RuntimeDocument.render(project, entry.readText(), isWatch))
        }
        if (updateScript) write(main, if (script.isFile) script.readText() else "")
        // 最后记录两份签名，中断时下次检查会补齐没有完成的产物。
        write(manifest, "$indexSignature\n$scriptSignature")
    }

    private fun fileStamp(file: File) = "${file.path}:${file.lastModified()}:${file.length()}\n"
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val CACHE_VERSION = "2:${RuntimeDocument.REVISION}"
    }
}
