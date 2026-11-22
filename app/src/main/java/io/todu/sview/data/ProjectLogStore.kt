package io.todu.sview.data

import java.io.File
import java.io.RandomAccessFile
import java.time.format.DateTimeFormatter

/** 日志位于项目私有根目录，不能作为网页资源读取；随项目目录一起删除。 */
class ProjectLogStore(private val projectDirectory: (String) -> File) {
    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private fun file(id: String) = File(projectDirectory(id), "runtime.log")

    /** 上层 IO 队列串行调用；删除后到达的迟滞消息不能重建项目目录。 */
    fun append(id: String, message: String) = appendBatch(id, listOf(ProjectLogRecord(id, message)))

    /** 同一项目的一批消息只打开并追加一次文件，裁剪也只检查一次。 */
    fun appendBatch(id: String, records: List<ProjectLogRecord>) {
        val target = file(id)
        if (target.parentFile?.isDirectory != true || records.isEmpty()) return
        val text = buildString {
            records.forEach { record ->
                val time = record.time.format(timestamp)
                record.message.take(16_384).lineSequence().forEach { append('[').append(time).append("] ").append(it).append('\n') }
            }
        }
        target.appendText(text)
        if (target.length() > 2 * 1024 * 1024) target.writeText(tail(target, 1024 * 1024))
    }

    fun size(id: String): Long = file(id).length()
    fun read(id: String): String = tail(file(id), 256 * 1024)

    /** 搜索整份保留日志，限制返回体积，避免将全部内容塞入界面。 */
    fun search(id: String, query: String): String {
        if (query.isBlank()) return read(id)
        val target = file(id)
        if (!target.isFile) return ""
        return target.bufferedReader().use { reader ->
            val result = StringBuilder()
            for (line in reader.lineSequence()) {
                if (line.contains(query, ignoreCase = true)) {
                    if (result.length + line.length > 128 * 1024) break
                    result.appendLine(line)
                }
            }
            result.toString()
        }
    }
    fun clear(id: String) { file(id).takeIf { it.exists() }?.writeText("") }
    fun export(id: String, output: java.io.OutputStream) {
        val source = file(id)
        if (source.isFile) source.inputStream().use { it.copyTo(output) }
    }

    /** 按完整行读取末尾，跳过截断的 UTF-8 字符，页面不加载整份历史。 */
    private fun tail(file: File, limit: Int): String {
        if (!file.exists()) return ""
        RandomAccessFile(file, "r").use { input ->
            val offset = (input.length() - limit).coerceAtLeast(0)
            input.seek(offset)
            val bytes = ByteArray((input.length() - offset).toInt())
            input.readFully(bytes)
            val start = if (offset == 0L) 0 else (bytes.indexOf(10.toByte()) + 1).coerceAtLeast(0)
            return bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8)
        }
    }
}
