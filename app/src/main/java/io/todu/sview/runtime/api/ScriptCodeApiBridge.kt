package io.todu.sview.runtime.api

import io.todu.sview.data.ProjectRepository
import org.json.JSONObject
import java.io.IOException

/** 读取原始模板，不经过运行入口生成或 HTML 注入。 */
internal class ScriptCodeApiBridge(private val repository: ProjectRepository, private val projectId: String) {
    fun invoke(request: JSONObject): JSONObject = try {
        val path = request.getJSONObject("args").get("file")
        require(path is String && path.isNotBlank()) { "file 必须是非空本地文件路径" }
        require(!path.startsWith("//") && !SCHEME.containsMatchIn(path) &&
            path.none { it == '\\' || it.isISOControl() }) { "仅支持本地文件路径" }
        // 两个候选都校验目录边界；全局同名文件优先，不能退回到项目副本掩盖读取错误。
        val global = repository.scoped(repository.templates, path)
        val local = repository.scoped(repository.root(projectId), path)
        val file = if (global.exists()) global else local
        val text = if (!file.exists()) "" else {
            if (!file.isFile) throw IOException("路径不是文件：$path")
            file.readText(Charsets.UTF_8)
        }
        JSONObject().put("ok", true).put("value", text)
    } catch (error: Exception) {
        ApiErrors.failure(error)
    }

    private companion object {
        val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    }
}
