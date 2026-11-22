package io.todu.sview.data

/** 网页和手机共用变量名、类型及重复名称校验。 */
object ProjectVariables {
    private val reserved = setOf("__proto__", "prototype", "constructor", "globalThis", "window", "document", "Object", "console",
            "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "export", "extends",
            "finally", "for", "function", "if", "import", "in", "instanceof", "new", "return", "super", "switch", "this", "throw", "try",
            "typeof", "var", "void", "while", "with", "yield", "let", "static", "enum", "await", "true", "false", "null")

    fun validate(values: List<ScriptVariable>) {
        require(values.map { it.name }.distinct().size == values.size) { "变量名已存在" }
        values.forEach { value ->
            require(value.name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*")) && value.name !in reserved) { "变量名无效" }
            require(value.type in ScriptVariable.TYPES) { "数据类型无效" }
            value.jsonValue()
        }
    }
}
