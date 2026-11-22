package io.todu.sview.data

/** 内置引用目录统一管理展示信息和资源路径，扩展库只需新增条目与对应资源。 */
data class ReferencePreset(val name: String, val version: String, val fileName: String, val type: String = "js") {
    val source: String get() = "/$fileName"
    fun createReference() = HeadReference(source = source, type = type)
}

object ReferenceCatalog {
    val globalStyle = ReferencePreset("global.css", "", "global.css", "css")
    val globalScript = ReferencePreset("global.js", "", "global.js")
    val items = listOf(globalStyle, globalScript, ReferencePreset("jQuery", "4.1.0", "jquery-4.1.0.js"),
        ReferencePreset("Lucide", "1.77.0", "lucide-1.77.0.js"))
    val systemTemplateNames: Set<String> = setOf("index.html") + items.map { it.fileName }
}
