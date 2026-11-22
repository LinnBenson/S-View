package io.todu.sview.lan

import android.util.Base64
import androidx.compose.ui.graphics.toArgb
import io.todu.sview.ui.components.CardColors
import io.todu.sview.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 在工作区 IO 锁内调用，网页与手机共用配置、资源与运行缓存。 */
class LanWorkspace(private val repository: ProjectRepository, private val cache: File) {
    private val textExtensions = setOf("js", "mjs", "cjs", "css", "html", "htm", "json", "txt", "md", "svg", "xml", "csv", "yaml", "yml")

    /** 修订号覆盖配置和文件元数据，另一端改动后拒绝旧版本写入。 */
    private data class Entry(val file: File, val path: String, val size: Long, val modified: Long, val directory: Boolean)
    private fun scan(projects: List<Project>): Map<File, List<Entry>> =
        (listOf(repository.templates) + projects.map { repository.root(it.id) }).associateWith { root ->
            root.walkTopDown().sortedBy { it.path }.map {
                Entry(it, it.relativeTo(root).invariantSeparatorsPath, it.length(), it.lastModified(), it.isDirectory)
            }.toList()
        }

    fun revision(projects: List<Project>, categories: List<String>): String = revision(projects, categories, scan(projects))

    private fun revision(projects: List<Project>, categories: List<String>, inventory: Map<File, List<Entry>>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun add(text: String) { hash.update(text.toByteArray()); hash.update(0.toByte()) }
        add(JSONArray(categories).toString())
        projects.forEach { add(ProjectJson.encode(it).toString()) }
        inventory.values.forEach { entries -> entries.forEach { entry ->
            add(entry.path); add(entry.size.toString()); add(entry.modified.toString())
        } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun snapshot(projects: List<Project>, categories: List<String>): JSONObject {
        val scanned = scan(projects)
        var total = 0L
        fun inventory(root: File): Pair<JSONObject, JSONArray> {
            val files = JSONObject(); val folders = JSONArray()
            scanned.getValue(root).filter { it.file != root }.forEach { entry ->
                val file = entry.file
                val path = entry.path
                if (entry.directory) folders.put(path)
                else {
                    val text = file.extension.lowercase() in textExtensions && file.length() <= 2 * 1024 * 1024 && total + file.length() <= 12 * 1024 * 1024
                    if (text) {
                        total += file.length()
                        files.put(path, JSONObject().put("kind", "text").put("text", file.readText()))
                    } else files.put(path, JSONObject().put("kind", "remote").put("size", file.length()).put("sourcePath", path))
                }
            }
            return files to folders
        }
        val (templates, folders) = inventory(repository.templates)
        return JSONObject().put("revision", revision(projects, categories, scanned)).put("categories", JSONArray(categories))
            .put("templates", templates).put("templateFolders", folders)
            .put("projects", JSONArray().apply {
                projects.forEach { project ->
                    val (files, directories) = inventory(repository.root(project.id))
                    put(ProjectJson.encode(project).put("colorCss", "#%06x".format(CardColors[project.color.coerceIn(CardColors.indices)].toArgb() and 0xffffff)).put("files", files).put("folders", directories))
                }
            }).put("protectedTemplates", JSONArray(ReferenceCatalog.systemTemplateNames.toList()))
    }

    fun file(id: String?, path: String, projects: List<Project>): File {
        if (id != null) require(projects.any { it.id == id }) { "项目不存在" }
        validatePath(path)
        return repository.scoped(if (id == null) repository.templates else repository.root(id), path).also {
            require(it.isFile) { "文件不存在" }
            require(it.length() <= 16 * 1024 * 1024) { "单个文件不能超过 16 MB" }
        }
    }

    /** 只接收本次修改的字段，未在网页展示的图标和引用始终保留。 */
    private fun config(json: JSONObject, previous: Project?): Project {
        val base = previous ?: Project(id = json.getString("id"), name = json.getString("name"))
        val name = json.optString("name", base.name).trim()
        require(name.isNotBlank() && name.length <= 100) { "项目名称应为 1 到 100 个字符" }
        repository.root(base.id)
        val variables = if (json.has("variables")) json.getJSONArray("variables").let { array ->
            List(array.length()) { index -> array.getJSONObject(index).let {
                ScriptVariable(it.getString("name"), it.getString("type"), it.getString("value"))
            } }.also(ProjectVariables::validate)
        } else base.variables
        return base.copy(variables = variables, name = name, category = json.optString("category", base.category),
            width = WindowDimension.parse(json.optString("width", base.width.format())),
            height = WindowDimension.parse(json.optString("height", base.height.format())),
            location = WindowLocation.parse(json.optString("location", base.location.value)),
            margins = if (json.has("margins")) WindowMargins.fromJson(json.getJSONObject("margins")) else base.margins,
            cornerRadius = json.optDouble("cornerRadius", base.cornerRadius),
            backdropTransparency = json.optDouble("backdropTransparency", base.backdropTransparency))
    }

    /** 失败时恢复已触及的源文件与配置，备份只覆盖修改范围。 */
    fun apply(body: JSONObject, projects: List<Project>, categories: List<String>): Pair<List<Project>, List<String>> {
        require(body.getString("revision") == revision(projects, categories)) { "工作区已在其他位置修改，请下载未保存文件后刷新网页" }
        val next = projects.toMutableList()
        var nextCategories = categories
        val operations = body.getJSONArray("operations")
        require(operations.length() <= 1000) { "单次修改数量过多" }
        val backup = File(cache, "lan-transaction-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val originals = linkedMapOf<File, File?>()
        fun preserve(file: File) {
            if (originals.keys.any { file == it || file.path.startsWith(it.path + File.separator) }) return
            val copy = if (file.exists()) File(backup, originals.size.toString()).also {
                check(file.copyRecursively(it, overwrite = true)) { "无法创建修改备份" }
            } else null
            originals[file] = copy
        }
        var restored = true
        try {
            for (i in 0 until operations.length()) {
                val op = operations.getJSONObject(i)
                when (op.getString("type")) {
                    "config" -> {
                        val value = op.getJSONObject("value")
                        val old = next.find { it.id == value.getString("id") }
                        val updated = config(value, old)
                        if (old == null) {
                            val root = repository.root(updated.id)
                            require(!root.parentFile!!.exists()) { "项目编号已存在" }
                            preserve(root.parentFile!!)
                            repository.createProject(updated); next.add(updated)
                        } else next[next.indexOf(old)] = updated
                    }
                    "deleteProject" -> {
                        val project = next.firstOrNull { it.id == op.getString("id") } ?: error("项目不存在")
                        preserve(repository.root(project.id).parentFile!!)
                        repository.deleteProject(project); next.remove(project)
                    }
                    "categories" -> {
                        val array = op.getJSONArray("value")
                        nextCategories = List(array.length()) { array.getString(it).trim() }
                        require(nextCategories.size <= 100 && nextCategories.distinct().size == nextCategories.size && nextCategories.all { it.isNotBlank() && it.length <= 100 && it != "全部" }) { "分类名称无效" }
                    }
                    "write", "delete", "mkdir" -> {
                        val id = if (op.isNull("id")) null else op.getString("id")
                        require(id == null || next.any { it.id == id }) { "项目不存在" }
                        val path = op.getString("path"); validatePath(path)
                        val root = if (id == null) repository.templates else repository.root(id)
                        val target = repository.scoped(root, path)
                        var changedRoot = target
                        while (changedRoot.parentFile != root && changedRoot.parentFile?.exists() == false) changedRoot = changedRoot.parentFile!!
                        preserve(changedRoot)
                        when (op.getString("type")) {
                            "write" -> {
                                val data = op.getJSONObject("value")
                                val bytes = when (data.getString("kind")) {
                                    "text" -> data.getString("text").toByteArray()
                                    "binary" -> Base64.decode(data.getString("data").substringAfter(','), Base64.DEFAULT)
                                    "remote" -> file(id, data.getString("sourcePath"), projects).readBytes()
                                    else -> error("文件内容无效")
                                }
                                require(bytes.size <= 16 * 1024 * 1024) { "单个文件不能超过 16 MB" }
                                require(!target.isDirectory) { "目标路径是文件夹" }
                                repository.writeBytes(id, path, bytes)
                            }
                            "mkdir" -> { if (!target.exists()) repository.create(id, path, true) else require(target.isDirectory) }
                            "delete" -> {
                                require(id == null || path != "main.js") { "主脚本不能删除" }
                                if (target.exists()) repository.delete(id, ResourceFile(path, target.isDirectory, false, target.length()))
                            }
                        }
                    }
                    else -> error("不支持的操作")
                }
            }
            repository.saveProjects(next)
            repository.saveCategories(nextCategories)
            return next to nextCategories
        } catch (error: Exception) {
            restored = false
            originals.entries.toList().asReversed().forEach { (file, copy) ->
                file.deleteRecursively()
                if (copy != null) check(copy.copyRecursively(file, overwrite = true)) { "恢复失败，备份保留于 ${backup.name}" }
            }
            repository.saveProjects(projects)
            repository.saveCategories(categories)
            repository.invalidateSourceCaches()
            restored = true
            throw error
        } finally { if (restored) backup.deleteRecursively() }
    }

    private fun validatePath(path: String) {
        require(path.length <= 1024 && path.isNotBlank() && !path.startsWith('/') && !path.endsWith('/') &&
            path.split('/').none { it in setOf("", ".", "..", "__proto__", "constructor", "prototype") } &&
            path.none { it < ' ' || it in "\\:*?\"<>|" }) { "文件路径无效" }
    }
}
