package io.todu.sview.wear

import io.todu.sview.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 同步包只带源码和配置；模板只传一份，资源缓存不进入手表。 */
object WatchSyncArchive {
    const val MAX_BYTES = 512L * 1024 * 1024
    const val BACKUP_BYTES = 8L * 1024 * 1024 * 1024
    private const val BACKUP_ENTRIES = 200000
    private const val BACKUP_MANIFEST_BYTES = 32 * 1024 * 1024
    private const val MAX_ENTRIES = 20000

    fun write(file: File, repository: ProjectRepository, projects: List<Project>, database: List<ScriptVariable>, tileIds: Set<String>, categories: List<String>? = null) {
        require(categories != null || projects.size <= 1000) { "同步项目过多" }
        require(tileIds.size <= WatchTileStore.MAX_PROJECTS && tileIds.all { id -> projects.any { it.id == id } })
        val limit = if (categories == null) MAX_BYTES else BACKUP_BYTES
        val maxEntries = if (categories == null) MAX_ENTRIES else BACKUP_ENTRIES
        var bytes = 0L
        var count = 0
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(path: String, source: File? = null, text: String? = null) {
                require(++count <= maxEntries) { "同步文件过多" }
                zip.putNextEntry(ZipEntry(path))
                if (source != null && source.isFile) source.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val size = input.read(buffer); if (size < 0) break
                        bytes += size; require(bytes <= limit) { "归档内容超过大小限制" }
                        zip.write(buffer, 0, size)
                    }
                }
                if (text != null) {
                    val data = text.toByteArray()
                    val manifestLimit = if (categories == null) 8 * 1024 * 1024 else BACKUP_MANIFEST_BYTES
                    require(data.size <= manifestLimit) { "归档配置过大" }
                    bytes += data.size
                    require(bytes <= limit) { "归档内容超过大小限制" }
                    zip.write(data)
                }
                zip.closeEntry()
            }
            fun tree(directory: File, prefix: String, excludeCache: Boolean) {
                require(directory.isDirectory) { "同步目录不存在" }
                directory.walkTopDown().onFail { _, error -> throw error }.onEnter { !Files.isSymbolicLink(it.toPath()) && !(excludeCache && it.parentFile == directory && it.name == "cache") }
                    .filter { it != directory && !Files.isSymbolicLink(it.toPath()) }
                    .forEach { item ->
                        val path = "$prefix/${item.relativeTo(directory).invariantSeparatorsPath}"
                        if (item.isDirectory) entry("$path/") else if (item.isFile) entry(path, item)
                    }
            }
            entry("manifest.json", text = JSONObject().put("version", 2)
                .apply { if (categories != null) put("format", "sview-backup").put("categories", JSONArray(categories)) }
                .put("tileProjects", JSONArray(projects.filter { it.id in tileIds }.map { it.id }))
                .put("projects", JSONArray().apply { projects.forEach { put(ProjectJson.encode(it)) } })
                .put("database", JSONArray().apply { database.forEach { put(JSONObject().put("name", it.name).put("type", it.type).put("value", it.value)) } }).toString())
            tree(repository.templates, "templates", false)
            projects.forEach {
                val root = repository.root(it.id)
                tree(root, "projects/${it.id}/files", categories == null)
                val log = File(root.parentFile, "runtime.log")
                if (categories != null && log.isFile) entry("projects/${it.id}/runtime.log", log)
            }
        }
        require(file.length() <= limit) { "归档文件超过大小限制" }
    }

    /** 解压时逐块限流并校验路径、类型和必要模板，失败不会更改当前工作区。 */
    data class Snapshot(val projects: List<Project>, val database: List<ScriptVariable>, val categories: List<String>)

    fun read(file: File, staging: File, backup: Boolean = false): Snapshot {
        val limit = if (backup) BACKUP_BYTES else MAX_BYTES
        val maxEntries = if (backup) BACKUP_ENTRIES else MAX_ENTRIES
        val manifestLimit = if (backup) BACKUP_MANIFEST_BYTES else 8 * 1024 * 1024
        // 完整备份须有 ZIP 中央目录，拒绝在最后一项资源之前截断的文件。
        val expectedEntries = if (backup) java.util.zip.ZipFile(file).use { it.size() } else null
        var total = 0L
        val names = hashSetOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(65536)
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.removeSuffix("/")
                require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && name.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "同步文件路径无效" }
                require(names.add(name) && names.size <= maxEntries) { "同步文件重复或数量过多" }
                require(name == "manifest.json" || name.startsWith("templates/") || name.startsWith("projects/")) { "同步内容无效" }
                if (backup && name.startsWith("projects/")) {
                    val parts = name.split('/')
                    require((parts.size >= 4 && parts[2] == "files") || (parts.size == 3 && parts[2] == "runtime.log" && !entry.isDirectory)) { "项目资源路径无效" }
                }
                val target = File(staging, name)
                require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                if (entry.isDirectory) { require(zip.read() == -1); check(target.mkdirs() || target.isDirectory) }
                else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        while (true) {
                            val size = zip.read(buffer); if (size < 0) break
                            total += size; require(total <= limit) { "解压内容超过大小限制" }
                            if (name == "manifest.json") require(target.length() + size <= manifestLimit) { "同步配置过大" }
                            output.write(buffer, 0, size)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val metadata = JSONObject(File(staging, "manifest.json").readText())
        require(expectedEntries == null || expectedEntries == names.size) { "备份文件不完整" }
        require(if (backup) metadata.optString("format") == "sview-backup" else !metadata.has("format")) { "归档类型不匹配" }
        val categories = if (backup) metadata.getJSONArray("categories").let { list ->
            List(list.length()) { list.getString(it) }
        } else emptyList()
        require(metadata.getInt("version") == 2) { "请更新手机与手表上的 S View" }
        val array = metadata.getJSONArray("projects")
        require(backup || array.length() <= 1000)
        val projects = List(array.length()) { ProjectJson.decode(array.getJSONObject(it)) }
        require(projects.map { it.id }.distinct().size == projects.size) { "项目重复" }
        projects.forEach {
            require(it.id.matches(Regex("[a-zA-Z0-9-]+"))) { "项目标识无效" }
            it.variables.forEach { variable -> variable.jsonValue() }
            if (backup) {
                require(it.name.isNotBlank()) { "项目名称不能为空" }
                ProjectVariables.validate(it.variables)
                it.references.forEach { reference -> reference.validate() }
            }
            val files = File(staging, "projects/${it.id}/files")
            check(files.mkdirs() || files.isDirectory) { "项目资源目录无效" }
        }
        require(File(staging, "projects").listFiles().orEmpty().all { folder -> projects.any { it.id == folder.name } }) { "存在未选择的项目" }
        ReferenceCatalog.systemTemplateNames.forEach { require(File(staging, "templates/$it").isFile) { "缺少模板：$it" } }
        val tileArray = metadata.getJSONArray("tileProjects")
        val tileIds = List(tileArray.length()) { tileArray.getString(it) }
        require(tileIds.size <= WatchTileStore.MAX_PROJECTS && tileIds.distinct().size == tileIds.size &&
            tileIds.all { id -> projects.any { it.id == id } }) { "磁贴项目无效" }
        if (!backup) File(staging, WatchTileStore.FILE_NAME).writeText(tileArray.toString())
        File(staging, "projects.json").writeText(array.toString())
        File(staging, "categories.json").writeText(JSONArray(categories).toString())
        val database = readDatabase(metadata.getJSONArray("database"))
        check(File(staging, "manifest.json").delete())
        return Snapshot(projects, database, categories)
    }

    /** 接收时再次检查数据库的总量与类型，写入前不接受重复键。 */
    private fun readDatabase(array: JSONArray): List<ScriptVariable> {
        require(array.length() <= 1000) { "数据库键过多" }
        val entries = List(array.length()) { array.getJSONObject(it).let { value ->
            DatabaseValue.normalized(ScriptVariable(value.getString("name"), value.getString("type"), value.getString("value")))
        } }
        require(entries.map { it.name }.distinct().size == entries.size)
        require(entries.sumOf { it.value.toByteArray().size.toLong() } <= 2 * 1024 * 1024)
        return entries
    }
}
