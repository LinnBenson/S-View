package io.todu.sview.data

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 项目备份只保存源文件和配置；全局资源快照还原为项目文件，不修改全局模板。 */
internal object ProjectArchive {
    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val MANIFEST = "project.json"

    /** 合并项目覆盖与全局资源，包含动态读取的模板资源及空目录。 */
    fun write(project: Project, files: File, templates: File, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            val manifest = JSONObject().put("format", "sview-project").put("version", 1)
                .put("project", ProjectJson.encode(project)).toString(4).toByteArray()
            require(manifest.size <= 1024 * 1024) { "项目配置超过 1 MB" }
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest); zip.closeEntry()
            var count = 1
            var total = manifest.size.toLong()
            fun collect(local: File?, global: File?, path: String) {
                val file = local?.takeIf { it.exists() } ?: global ?: return
                require(listOfNotNull(local, global).none { java.nio.file.Files.isSymbolicLink(it.toPath()) }) { "不能导出符号链接" }
                require(++count <= MAX_ENTRIES) { "项目文件数量超过限制" }
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("files/$path/")); zip.closeEntry()
                    val localChildren = local?.takeIf { it.isDirectory }?.listFiles().orEmpty().associateBy { it.name }
                    val globalChildren = global?.takeIf { it.isDirectory }?.listFiles().orEmpty().associateBy { it.name }
                    (globalChildren.keys + localChildren.keys).sorted().forEach { name ->
                        collect(localChildren[name], globalChildren[name], "$path/$name")
                    }
                } else {
                    require(file.isFile) { "无法读取项目文件" }
                    zip.putNextEntry(ZipEntry("files/$path"))
                    file.inputStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_BYTES) { "项目超过 512 MB" }
                            zip.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val local = files.listFiles().orEmpty().associateBy { it.name }
            val global = templates.listFiles().orEmpty().associateBy { it.name }
            (global.keys + local.keys).sorted().forEach { collect(local[it], global[it], it) }
        }
    }

    /** 只解压到新建的暂存目录；完成全部校验后，调用方再提交到项目目录。 */
    fun read(input: InputStream, staging: File): Project {
        require(staging.isDirectory && staging.list().orEmpty().isEmpty()) { "导入暂存目录无效" }
        val names = hashSetOf<String>()
        var total = 0L
        var manifest: String? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.removeSuffix("/")
                require(names.add(name) && names.size <= MAX_ENTRIES) { "压缩包包含重复路径或文件过多" }
                require(name.isNotEmpty() && !name.contains('\\') && !name.contains('\u0000') &&
                    name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "压缩包路径无效" }
                require(name == MANIFEST || name.startsWith("files/")) { "不是有效的 S View 项目压缩包" }
                val target = File(staging, name).canonicalFile
                require(target.path.startsWith(staging.canonicalPath + File.separator)) { "压缩包路径超出目录" }
                if (entry.isDirectory) {
                    require(name != MANIFEST && (target.mkdirs() || target.isDirectory)) { "目录冲突" }
                } else {
                    require(!target.exists()) { "压缩包文件与目录冲突" }
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory) { "无法创建目录" }
                    var size = 0L
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read; size += read
                            require(total <= MAX_BYTES && (name != MANIFEST || size <= 1024 * 1024)) { "压缩包解压大小超过限制" }
                            output.write(buffer, 0, read)
                        }
                    }
                    if (name == MANIFEST) manifest = target.readText()
                }
                zip.closeEntry()
            }
        }
        val json = JSONObject(requireNotNull(manifest) { "压缩包缺少项目配置" })
        require(json.getString("format") == "sview-project" && json.getInt("version") == 1) { "不支持的项目压缩包格式" }
        val project = ProjectJson.decode(json.getJSONObject("project")).copy(id = UUID.randomUUID().toString())
        require(project.name.isNotBlank()) { "项目名称不能为空" }
        require(project.variables.map { it.name }.distinct().size == project.variables.size) { "全局变量重名" }
        project.variables.forEach {
            require(it.type in ScriptVariable.TYPES) { "变量类型无效" }
            it.jsonValue()
        }
        project.references.forEach { it.validate() }
        require(File(staging, "files/index.html").isFile) { "压缩包缺少 index.html" }
        return project
    }
}
