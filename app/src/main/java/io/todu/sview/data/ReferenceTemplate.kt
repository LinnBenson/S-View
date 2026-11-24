package io.todu.sview.data

/** 引用只在入口模板明确预留的位置生成，不再隐式追加到 head。 */
object ReferenceTemplate {
    const val IS_WATCH_PLACEHOLDER = "<!-- sview:iswatch -->"
    const val VARIABLES_PLACEHOLDER = "<!-- sview:variables -->"
    const val PLACEHOLDER = "<!-- sview:references -->"

    fun render(html: String, references: List<HeadReference>): String =
        html.replace(PLACEHOLDER, references.joinToString("\n") { it.toHtml() })
}
