package io.todu.sview.storage

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** 先登记再创建，异常退出后只回收登记过的文件，不扫描设备共享存储。 */
class TemporaryFiles private constructor(context: Context) {
    private val journal = AtomicFile(File(context.noBackupFilesDir, "temporary-files.json"))
    private val active = mutableSetOf<String>()

    private fun read(): MutableSet<String> {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return mutableSetOf()
        val array = journal.openRead().bufferedReader().use { JSONArray(it.readText()) }
        return MutableList(array.length()) { array.getString(it) }.toMutableSet()
    }

    private fun save(paths: Set<String>) {
        val output = journal.startWrite()
        try {
            output.write(JSONArray(paths.toList()).toString().toByteArray())
            journal.finishWrite(output)
        } catch (error: Exception) { journal.failWrite(output); throw error }
    }

    @Synchronized fun create(directory: File, prefix: String, suffix: String): File = createEntry(directory, prefix, suffix, false)

    /** 目录暂存同样写入恢复日志，进程退出后可递归清理。 */
    @Synchronized fun createDirectory(directory: File, prefix: String): File = createEntry(directory, prefix, ".part", true)

    private fun createEntry(directory: File, prefix: String, suffix: String, directoryEntry: Boolean): File {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建临时目录" }
        val file = File(directory.canonicalFile, "$prefix${UUID.randomUUID()}$suffix")
        val paths = read().apply { add(file.path) }
        save(paths)
        try { if (directoryEntry) Files.createDirectory(file.toPath()) else Files.createFile(file.toPath()) }
        catch (error: Exception) { save(paths.apply { remove(file.path) }); throw error }
        active.add(file.path)
        return file
    }

    /** 已移动成功的文件只移除记录；删除失败保留记录供下次重试。 */
    @Synchronized fun release(file: File) {
        active.remove(file.path)
        val paths = read()
        if (file.path !in paths) return
        if (deleteRegistered(file.path)) { paths.remove(file.path); save(paths) }
    }

    @Synchronized fun cleanup() {
        val paths = read()
        paths.toList().filterNot { it in active }.forEach { if (deleteRegistered(it)) paths.remove(it) }
        save(paths)
        check(paths.all { it in active }) { "部分临时文件无法清理，请检查设备存储权限后重试" }
    }

    @Synchronized fun isActive(file: File): Boolean = file.absolutePath in active

    private fun deleteRegistered(path: String): Boolean = runCatching {
        val file = File(path)
        // 父目录被替换为符号链接时不沿链接删除其他位置的文件。
        if (file.parentFile?.canonicalPath != file.parent) return@runCatching false
        if (Files.isDirectory(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(file.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(entry: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.delete(entry)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(entry: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                    if (error != null) throw error
                    Files.delete(entry)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        } else Files.deleteIfExists(file.toPath())
        true
    }.getOrDefault(false)

    companion object {
        @Volatile private var instance: TemporaryFiles? = null
        fun get(context: Context): TemporaryFiles = instance ?: synchronized(this) {
            instance ?: TemporaryFiles(context.applicationContext).also { instance = it }
        }
    }
}
