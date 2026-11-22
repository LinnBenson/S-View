package io.todu.sview.runtime.storage

import java.io.File
import java.nio.file.Files

/** 项目与设备共用路径规则；拒绝父级跳转和符号链接，递归操作不会越过所选目录。 */
internal class StoragePaths(private val projectRoot: File, private val deviceRoot: () -> File) {
    data class Entry(val file: File, val root: File, val path: String, val device: Boolean) {
        fun requireChild() { require(file != root) { "不能修改存储根目录" } }
    }

    fun resolve(path: String): Entry {
        val device = path.startsWith("device:/")
        require(!path.startsWith("device:") || device) { "设备路径应以 device:/ 开头" }
        val relative = if (device) path.removePrefix("device:/") else path.removePrefix("/")
        require('\\' !in relative && relative.none { it.code < 32 || it.code == 127 }) { "文件路径无效" }
        val parts = relative.split('/').filter { it.isNotEmpty() }
        require(parts.none { it == "." || it == ".." }) { "文件路径不能包含 . 或 .." }
        val root = (if (device) deviceRoot() else projectRoot).canonicalFile
        var file = root
        parts.forEach {
            file = File(file, it)
            require(!Files.isSymbolicLink(file.toPath())) { "不能操作符号链接" }
        }
        file = file.canonicalFile
        require(file == root || file.path.startsWith(root.path + File.separator)) { "文件路径超出存储目录" }
        return Entry(file, root, (if (device) "device:/" else "") + parts.joinToString("/"), device)
    }
}
