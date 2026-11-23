package io.todu.sview.data

/** 日志只按当前实例 ID 或唯一的完整名称定位，不把重名项目中的第一个当作结果。 */
internal object ProjectLogTarget {
    class Failure(val code: String, message: String) : IllegalArgumentException(message)

    fun resolve(projects: List<Project>, currentId: String, name: String?): String {
        if (name == null) return projects.firstOrNull { it.id == currentId }?.id
            ?: throw Failure("PROJECT_NOT_FOUND", "当前项目不存在")
        require(name.isNotBlank()) { "项目名称不能为空白" }
        val matches = projects.filter { it.name == name }
        if (matches.isEmpty()) throw Failure("PROJECT_NOT_FOUND", "项目不存在：$name")
        if (matches.size > 1) throw Failure("PROJECT_NAME_AMBIGUOUS", "存在同名项目，无法确定日志来源：$name")
        return matches.single().id
    }
}
