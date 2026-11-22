package io.todu.sview.lan

import io.todu.sview.data.SharedDatabase
import io.todu.sview.data.ScriptVariable
import org.json.JSONArray
import org.json.JSONObject

/** 网页沿用手机表单的逐项事务和原值校验，不全量覆盖共享数据库。 */
class LanDatabase(private val database: SharedDatabase) {
    fun handle(action: String, body: JSONObject): LanResponse {
        when (action) {
            "database/edit" -> database.edit(decode(body.getJSONObject("value")),
                if (body.isNull("previous")) null else decode(body.getJSONObject("previous")))
            "database/remove" -> decode(body.getJSONObject("previous")).let { database.remove(it.name, it) }
            "database/list" -> Unit
            else -> error("接口不存在")
        }
        return LanResponse.json(JSONObject().put("entries", JSONArray().apply {
            database.list().forEach { put(JSONObject().put("name", it.name).put("type", it.type).put("value", it.value)) }
        }))
    }

    private fun decode(value: JSONObject) = ScriptVariable(value.getString("name"), value.getString("type"), value.getString("value"))
}
