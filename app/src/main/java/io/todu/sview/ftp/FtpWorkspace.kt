package io.todu.sview.ftp

import io.todu.sview.data.Project
import io.todu.sview.data.ProjectRepository
import io.todu.sview.data.ReferenceCatalog
import io.todu.sview.data.ResourceFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 协议层仅传递虚拟路径，不能直接访问手机磁盘。 */
data class FtpRequest(val action: String, val path: String, val destination: String? = null, val bytes: ByteArray? = null, val session: Int = 0)
data class FtpEntry(val name: String, val directory: Boolean, val size: Long = 0, val modified: Long = 0)
data class FtpResult(val entries: List<FtpEntry> = emptyList(), val bytes: ByteArray? = null)

/** 在工作区修改锁和 IO 队列内执行，与网页编辑和手表同步读取同一份源文件。 */
class FtpWorkspace(private val repository: ProjectRepository) {
    private data class Target(val id: String?, val path: String, val file: File)

    /** 名称仅用于展示，完整 ID 保证重名项目仍能稳定定位。 */
    private fun projectName(project: Project): String =
        project.name.map { if (it < ' ' || it in "/\\\"*") '_' else it }.joinToString("").take(100) + " [${project.id}]"

    private fun target(path: String, projects: List<Project>): Target {
        val parts = path.trim('/').split('/')
        val id: String?
        val relative: String
        when {
            parts.first() == "全局模板" -> { id = null; relative = parts.drop(1).joinToString("/") }
            parts.size >= 2 && parts.first() == "项目" -> {
                id = projects.firstOrNull { projectName(it) == parts[1] }?.id ?: error("项目不存在")
                relative = parts.drop(2).joinToString("/")
            }
            else -> error("目录不存在")
        }
        require(relative.split('/').none { it == "." || it == ".." } && relative.none { it < ' ' || it == '\\' }) { "文件路径无效" }
        val root = if (id == null) repository.templates else repository.root(id)
        // 不跟随资源中的符号链接，避免别名绕过受保护文件检查。
        var current = root
        for (part in relative.split('/').filter { it.isNotEmpty() }) {
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) { "不支持符号链接" }
        }
        return Target(id, relative, repository.scoped(root, relative))
    }

    private fun entry(file: File) = FtpEntry(file.name, file.isDirectory, if (file.isFile) file.length() else 0, file.lastModified())

    private fun mutable(target: Target, removing: Boolean = false) {
        require(target.path.isNotEmpty()) { "不能修改项目根目录" }
        if (removing) {
            require(target.id == null || target.path != "main.js") { "主脚本不能删除或移动" }
            if (target.id == null) require(ReferenceCatalog.systemTemplateNames.none {
                it == target.path || it.startsWith(target.path + "/")
            }) { "系统模板不能删除或移动" }
        }
    }

    fun execute(request: FtpRequest, projects: List<Project>): FtpResult {
        val path = request.path
        val virtual = when (path) {
            "/" -> listOf(FtpEntry("项目", true), FtpEntry("全局模板", true))
            "/项目" -> projects.map { FtpEntry(projectName(it), true) }
            else -> null
        }
        if (virtual != null) {
            require(request.action in setOf("list", "stat")) { "不能修改此目录" }
            return FtpResult(if (request.action == "list") virtual else listOf(FtpEntry(path.substringAfterLast('/'), true)))
        }
        val target = target(path, projects)
        val file = target.file
        when (request.action) {
            "stat" -> { require(file.exists()) { "文件不存在" }; return FtpResult(listOf(entry(file))) }
            "list" -> {
                require(file.isDirectory) { "目录不存在" }
                val children = requireNotNull(file.listFiles()) { "无法读取目录" }
                return FtpResult(children.filter { !Files.isSymbolicLink(it.toPath()) && it.name.none { c -> c < ' ' } }
                    .sortedBy { it.name }.map(::entry))
            }
            "read" -> {
                require(file.isFile && file.length() <= MAX_FILE_BYTES) { "文件不存在或超过 16 MB" }
                return FtpResult(bytes = file.inputStream().use(FtpSession::readFile))
            }
            "write" -> {
                mutable(target)
                val bytes = requireNotNull(request.bytes)
                require(bytes.size <= MAX_FILE_BYTES && !file.isDirectory) { "文件过大或目标是目录" }
                repository.writeBytes(target.id, target.path, bytes)
            }
            "mkdir" -> { mutable(target); repository.create(target.id, target.path, true) }
            "delete", "rmdir" -> {
                mutable(target, removing = true)
                require(file.exists()) { "文件不存在" }
                if (request.action == "rmdir") require(file.isDirectory && file.list()?.isEmpty() == true) { "目录非空" }
                else require(file.isFile) { "目标不是文件" }
                repository.delete(target.id, ResourceFile(target.path, file.isDirectory, false, file.length()))
            }
            "rename" -> {
                mutable(target, removing = true)
                val next = target(requireNotNull(request.destination), projects)
                mutable(next)
                require(target.id == next.id && file.exists() && !next.file.isDirectory) { "不能跨项目移动或覆盖目录" }
                require(next.file.parentFile?.isDirectory == true) { "目标目录不存在" }
                // 编辑器可先上传临时文件，再用原子重命名替换正文。
                Files.move(file.toPath(), next.file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                repository.invalidateSourceCaches()
            }
            else -> error("不支持的文件操作")
        }
        return FtpResult()
    }

    companion object { const val MAX_FILE_BYTES = 16 * 1024 * 1024 }
}
