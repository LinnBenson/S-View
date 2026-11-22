package io.todu.sview.runtime.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 分块文件桥。写入先暂存，提交前不修改原文件；窗口销毁时释放句柄并清理未提交文件。 */
internal class ScriptStorage(
    projectRoot: File,
    deviceRoot: () -> File,
    private val temporaryStore: io.todu.sview.storage.TemporaryFiles,
    private val readChunkSize: Int = 65536,
    private val changeProject: (String, () -> Unit) -> Unit
) : Closeable {
    private val paths = StoragePaths(projectRoot, deviceRoot)
    private val closed = AtomicBoolean(false)
    private val reads = mutableMapOf<String, InputStream>()
    private data class Write(val entry: StoragePaths.Entry, val temporary: File, val output: OutputStream, var size: Long)
    private val writes = mutableMapOf<String, Write>()

    companion object {
        // 不同运行窗口共享写入占用表，避免追加丢失或删除正在保存的目录。
        private val lock = Any()
        private val busy = mutableSetOf<File>()
        private val temporaryFiles = mutableSetOf<File>()
        const val CHUNK_SIZE = 262144
    }

    /** 控制请求返回 JSON；复制正文在原生处理，其余文件正文使用独立二进制操作。 */
    fun command(operation: String, args: JSONObject): Any {
        if (operation == "copy") return copy(args)
        return synchronized(lock) {
            check(!closed.get()) { "运行窗口已关闭" }
            when (operation) {
                "readStart" -> open(paths.resolve(args.getString("path")), args.optBoolean("stream"))
                "writeStart" -> beginWrite(paths.resolve(args.getString("path")), args.optBoolean("append"))
                "commit" -> commit(args.getString("id"))
                "cancel" -> { cancel(args.getString("id")); true }
                else -> fileCommand(operation, paths.resolve(args.getString("path")))
            }
        }
    }

    /** 复用已有目标占用检查和源码变更通知；复制由原生完成，不传递文件正文到网页。 */
    private fun copy(args: JSONObject): String {
        val overwrite = args.opt("overwrite") ?: false
        require(overwrite is Boolean) { "overwrite 应为 boolean" }
        val (source, destination) = synchronized(lock) {
            check(!closed.get()) { "运行窗口已关闭" }
            val from = paths.resolve(args.getString("source"))
            val to = paths.resolve(args.getString("destination"))
            requireAvailable(from.file)
            requireAvailable(to.file)
            busy.add(from.file)
            busy.add(to.file)
            Pair(from, to)
        }
        try {
            // 大文件复制不持有全局锁；预留来源和目标，其他窗口仍可读取或操作无关文件。
            return StorageCopy(temporaryStore, {
                check(!closed.get() && !Thread.currentThread().isInterrupted) { "复制已取消" }
            }, { file, active -> synchronized(lock) {
                if (active) temporaryFiles.add(file) else temporaryFiles.remove(file)
            } }).execute(source, destination, overwrite, {
                require(paths.resolve(source.path).file == source.file && paths.resolve(destination.path).file == destination.file) { "文件路径已变化" }
            }, { action -> synchronized(lock) { mutate(destination, action) } })
        } finally {
            synchronized(lock) { busy.remove(source.file); busy.remove(destination.file) }
        }
    }

    private fun fileCommand(operation: String, entry: StoragePaths.Entry): Any {
        val file = entry.file
        if (operation == "hasDirectory" || operation == "hasFile") return hasEntry(file, operation == "hasDirectory")
        if (operation == "list") {
            require(file.isDirectory) { "目录不存在" }
            val files = file.listFiles() ?: error("无法读取目录")
            return JSONArray().apply {
                files.filterNot { it in temporaryFiles }.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name }).forEach {
                    // 不跟随目录内的链接，也不把它们暴露成可操作文件。
                    if (!Files.isSymbolicLink(it.toPath())) put(info(it, entry.path.trimEnd('/') + "/" + it.name))
                }
            }
        }
        entry.requireChild()
        requireAvailable(file)
        when (operation) {
            "createDirectory" -> { require(!file.exists() || file.isDirectory) { "同名文件已存在" }; check(file.isDirectory || file.mkdirs()) { "创建目录失败" }; return true }
            "createFile" -> mutate(entry) {
                parent(file)
                Files.createFile(file.toPath())
            }
            "deleteFile" -> {
                if (!file.exists()) return false
                require(file.isFile) { "目标不是文件" }
                mutate(entry) { Files.delete(file.toPath()) }
            }
            "deleteDirectory" -> {
                if (!file.exists()) return false
                require(file.isDirectory) { "目标不是目录" }
                mutate(entry) { deleteTree(file) }
            }
            else -> error("未知文件操作")
        }
        return true
    }

    private fun info(file: File, path: String) = JSONObject().put("name", file.name).put("path", path.removePrefix("/"))
        .put("directory", file.isDirectory).put("size", if (file.isDirectory) 0 else file.length()).put("lastModified", file.lastModified())

    /** 查询不占用写锁目标；不存在返回 false，权限等其他错误仍交给调用方处理。 */
    private fun hasEntry(file: File, directory: Boolean): Boolean {
        if (file in temporaryFiles) return false
        val attributes = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        } catch (_: java.nio.file.NoSuchFileException) {
            return false
        } catch (_: java.nio.file.NotDirectoryException) {
            return false
        }
        return if (directory) attributes.isDirectory else attributes.isRegularFile
    }

    private fun open(entry: StoragePaths.Entry, stream: Boolean): JSONObject {
        require(reads.size < 8) { "打开的文件过多，请先读取或取消已有流" }
        require(entry.file.isFile) { "文件不存在或不是文件" }
        require(stream || entry.file.length() <= 32L * 1024 * 1024) { "文件超过 32 MB，请使用 open(path, 'stream')" }
        val id = UUID.randomUUID().toString()
        val input = entry.file.inputStream()
        reads[id] = input
        return info(entry.file, entry.path).put("id", id)
            .put("type", URLConnection.guessContentTypeFromName(entry.file.name) ?: "application/octet-stream")
    }

    /** 每次只读取一块；空块表示结束，并立即关闭句柄。 */
    fun read(id: String): ByteArray = synchronized(lock) {
        check(!closed.get()) { "运行窗口已关闭" }
        val input = reads[id] ?: error("文件读取已结束")
        try {
            val buffer = ByteArray(readChunkSize)
            val count = input.read(buffer)
            if (count < 0) { cancel(id); byteArrayOf() } else buffer.copyOf(count)
        } catch (error: Exception) { cancel(id); throw error }
    }

    /** 锁定目标后复制追加部分；原文件在所有正文成功接收前保持不变。 */
    private fun beginWrite(entry: StoragePaths.Entry, append: Boolean): JSONObject {
        entry.requireChild()
        require(writes.size < 4) { "同时保存的文件过多" }
        requireAvailable(entry.file)
        require(!entry.file.exists() || entry.file.isFile) { "目标不是文件" }
        parent(entry.file)
        val temporary = temporaryStore.create(entry.file.parentFile!!, ".sview-write-", ".part")
        val id = UUID.randomUUID().toString()
        try {
            val output = temporary.outputStream()
            val write = Write(entry, temporary, output, 0)
            writes[id] = write
            busy.add(entry.file)
            temporaryFiles.add(temporary)
            if (append && entry.file.exists()) entry.file.inputStream().use { input ->
                val buffer = ByteArray(readChunkSize)
                while (true) {
                    check(!closed.get()) { "运行窗口已关闭" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    write.size += count
                }
            }
            return JSONObject().put("id", id)
        } catch (error: Exception) { cancel(id); temporaryStore.release(temporary); throw error }
    }

    fun write(id: String, bytes: ByteArray) = synchronized(lock) {
        check(!closed.get()) { "运行窗口已关闭" }
        require(bytes.size <= CHUNK_SIZE)
        val write = writes[id] ?: error("文件保存已结束")
        try { write.output.write(bytes); write.size += bytes.size }
        catch (error: Exception) { cancel(id); throw error }
    }

    /** 成功后发布完整文件；入口或 main.js 的变化交给仓库同步运行缓存。 */
    private fun commit(id: String): JSONObject {
        val write = writes[id] ?: error("文件保存已结束")
        try {
            write.output.close()
            val current = paths.resolve(write.entry.path)
            require(current.file == write.entry.file) { "目标目录已变化" }
            mutate(current) {
                Files.move(write.temporary.toPath(), current.file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return JSONObject().put("path", current.path).put("size", write.size)
        } finally { cancel(id) }
    }

    private fun mutate(entry: StoragePaths.Entry, action: () -> Unit) {
        if (entry.device) action() else changeProject(entry.path, action)
    }

    private fun parent(file: File) {
        val parent = file.parentFile ?: error("文件路径无效")
        check(parent.isDirectory || parent.mkdirs()) { "无法创建父目录" }
    }

    private fun requireAvailable(file: File) {
        require(busy.none { it == file || it.path.startsWith(file.path + File.separator) || file.path.startsWith(it.path + File.separator) }) { "目标正在保存，请稍后重试" }
    }

    /** walkFileTree 默认不跟随链接，目录中的链接只删除链接自身。 */
    private fun deleteTree(file: File) {
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(!closed.get()) { "运行窗口已关闭" }
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                check(!closed.get()) { "运行窗口已关闭" }
                Files.delete(path)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun cancel(id: String) {
        reads.remove(id)?.close()
        writes.remove(id)?.let {
            try { it.output.close() } finally {
                temporaryStore.release(it.temporary)
                temporaryFiles.remove(it.temporary)
                busy.remove(it.entry.file)
            }
        }
    }

    override fun close() {
        closed.set(true)
        synchronized(lock) {
            (reads.keys + writes.keys).toList().forEach { id ->
                io.todu.sview.runtime.cleanupResource("文件操作") { cancel(id) }
            }
        }
    }
}
