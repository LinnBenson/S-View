package io.todu.sview.runtime.storage

import io.todu.sview.storage.TemporaryFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** 在目标所在文件系统暂存完整副本，复制期间不改写目标。调用方预留来源与目标，提交时持有文件操作锁。 */
internal class StorageCopy(private val temporaryStore: TemporaryFiles, private val ensureActive: () -> Unit,
    private val track: (File, Boolean) -> Unit) {
    fun execute(source: StoragePaths.Entry, destination: StoragePaths.Entry, overwrite: Boolean,
        revalidate: () -> Unit, publish: (() -> Unit) -> Unit): String {
        ensureActive()
        destination.requireChild()
        val from = source.file
        val to = destination.file
        require(from != to && (!to.exists() || !Files.isSameFile(from.toPath(), to.toPath()))) { "不能复制到自身" }
        val attributes = Files.readAttributes(from.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile || attributes.isDirectory) { "来源必须是普通文件或目录" }
        if (attributes.isDirectory) require(!to.path.startsWith(from.path + File.separator)) { "不能将目录复制到自己的子目录" }
        validateTarget(to, attributes.isDirectory, overwrite)
        val parent = to.parentFile ?: error("目标路径无效")
        check(parent.isDirectory || parent.mkdirs()) { "无法创建目标父目录" }
        revalidate()
        val temporary = if (attributes.isDirectory) temporaryStore.createDirectory(parent, ".sview-copy-")
            else temporaryStore.create(parent, ".sview-copy-", ".part")
        track(temporary, true)
        try {
            if (attributes.isDirectory) copyDirectory(from.toPath(), temporary.toPath())
            else copyFile(from.toPath(), temporary.toPath())
            ensureActive()
            revalidate()
            validateTarget(to, attributes.isDirectory, overwrite)
            publish {
                ensureActive()
                // 覆盖要求同文件系统原子替换；不支持时保留原文件并报告失败。
                if (overwrite && !attributes.isDirectory) Files.move(temporary.toPath(), to.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                else Files.move(temporary.toPath(), to.toPath())
            }
            return destination.path
        } finally {
            try { temporaryStore.release(temporary) } finally { track(temporary, false) }
        }
    }

    private fun validateTarget(file: File, directory: Boolean, overwrite: Boolean) {
        require(!Files.isSymbolicLink(file.toPath())) { "不能操作符号链接" }
        if (file.exists()) {
            require(!directory && overwrite && file.isFile) { "目标已存在；仅文件可通过 overwrite 覆盖" }
        }
    }

    /** 遍历不跟随符号链接；发现链接或特殊文件时整个暂存目录回滚。 */
    private fun copyDirectory(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                ensureActive()
                if (path != source) Files.createDirectory(target.resolve(source.relativize(path)))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                ensureActive()
                require(attrs.isRegularFile && !attrs.isSymbolicLink) { "目录中包含符号链接或特殊文件" }
                val output = target.resolve(source.relativize(path))
                Files.createFile(output)
                copyFile(path, output)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** 固定大小缓冲区，不受文件总大小影响；关闭窗口时在块边界停止。 */
    private fun copyFile(source: Path, target: Path) {
        Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
            Files.newOutputStream(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }
}
