package io.todu.sview.data

import org.json.JSONArray
import org.json.JSONObject

/** 项目目录与导出包共用配置格式，避免导入遗漏新增字段。 */
internal object ProjectJson {
    fun encode(project: Project): JSONObject = JSONObject().apply {
        put("id", project.id); put("name", project.name); put("category", project.category)
        put("icon", project.icon); put("color", project.color); put("pinned", project.pinned)
        put("location", project.location.value)
        put("shareSource", project.shareSource.value)
        put("margins", project.margins.toJson())
        put("cornerRadius", project.cornerRadius)
        put("backdropTransparency", project.backdropTransparency)
        put("width", project.width.format()); put("height", project.height.format())
        put("variables", JSONArray().apply {
            project.variables.forEach { put(JSONObject().put("name", it.name).put("type", it.type).put("value", it.value)) }
        })
        put("references", JSONArray().apply { project.references.forEach { put(it.toJson()) } })
    }

    fun decode(json: JSONObject): Project {
        val variables = json.getJSONArray("variables")
        val references = json.getJSONArray("references")
        return Project(json.getString("id"), json.getString("name"), json.getString("category"),
            json.getInt("color"), json.getBoolean("pinned"), WindowDimension.parse(json.getString("width")), WindowDimension.parse(json.getString("height")),
            List(variables.length()) { variables.getJSONObject(it).let { item -> ScriptVariable(item.getString("name"), item.getString("type"), item.getString("value")) } },
            List(references.length()) { HeadReference.fromJson(references.getJSONObject(it)) }, json.optString("icon", "code"), WindowLocation.parse(json.optString("location", "center")),
            if (json.has("cornerRadius")) json.getDouble("cornerRadius") else 20.0,
            if (json.has("backdropTransparency")) json.getDouble("backdropTransparency") else 40.0,
            if (json.has("margins")) WindowMargins.fromJson(json.getJSONObject("margins")) else WindowMargins(),
            ShareSource.parse(json.optString("shareSource", "disabled")))
    }
}
