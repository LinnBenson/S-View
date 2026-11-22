package io.todu.sview.data

import org.json.JSONArray
import org.json.JSONObject

/** 数据库复用变量的类型与编辑模型，但键不受 JavaScript 标识符规则限制。 */
object DatabaseValue {
    const val MAX_VALUE_BYTES = 48 * 1024

    fun validateKey(key: String) {
        require(key.isNotBlank() && key.length <= 128 && key.none { it.isISOControl() }) { "键应为 1 到 128 个字符，不能包含控制字符" }
    }

    fun fromValue(key: String, value: Any): ScriptVariable {
        validateKey(key)
        val type = when (value) {
            is Boolean -> "boolean"
            is String -> "string"
            is Number -> { require(value.toDouble().isFinite()) { "数字必须为有限值" }; "number" }
            is JSONArray -> "array"
            is JSONObject -> "object"
            else -> throw IllegalArgumentException("值仅支持 boolean、string、number、array、object")
        }
        val text = if (value is String) value else value.toString()
        require(text.toByteArray().size <= MAX_VALUE_BYTES) { "单个值不能超过 48 KB" }
        return ScriptVariable(key, type, text)
    }

    fun normalized(entry: ScriptVariable): ScriptVariable {
        require(entry.type in ScriptVariable.TYPES) { "数据类型无效" }
        return fromValue(entry.name, json(entry))
    }

    fun json(entry: ScriptVariable?): Any = entry?.jsonValue() ?: JSONObject.NULL
}
