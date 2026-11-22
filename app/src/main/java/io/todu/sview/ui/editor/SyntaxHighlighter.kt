package io.todu.sview.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import io.noties.prism4j.Prism4j
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 单个后台通道复用语法缓存，连续输入只保留最新任务，避免多份大文本并行解析。 */
internal object SyntaxHighlighter {
    private const val MAX_HIGHLIGHT_BYTES = 10 * 1024

    /** 先按字符数排除大文本，最多编码 10 KB 字符，避免为大文件分配额外字节数组。 */
    fun canHighlight(text: String): Boolean =
        text.length <= MAX_HIGHLIGHT_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_HIGHLIGHT_BYTES

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val engine by lazy { EditorGrammar.create() }

    fun language(fileName: String): String? = when (fileName.substringAfterLast('.').lowercase()) {
        "js", "mjs", "cjs" -> "javascript"
        "html", "htm", "xml", "svg" -> "markup"
        "css" -> "css"
        "json" -> "json"
        else -> null
    }

    /** 不完整的编辑输入允许退回纯文本；取消任务不发布过期的字符范围。 */
    suspend fun highlight(text: String, language: String?): AnnotatedString =
        if (language == null || !canHighlight(text)) AnnotatedString(text) else withContext(dispatcher) {
            coroutineContext.ensureActive()
            val grammar = engine.grammar(language) ?: return@withContext AnnotatedString(text)
            val nodes = try { engine.tokenize(text, grammar) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                return@withContext AnnotatedString(text)
            } catch (_: StackOverflowError) {
                return@withContext AnnotatedString(text)
            }
            coroutineContext.ensureActive()
            val builder = AnnotatedString.Builder(text)
            fun append(nodes: List<Prism4j.Node>, start: Int): Int {
                var offset = start
                for (node in nodes) {
                    val end = (offset + node.textLength()).coerceAtMost(text.length)
                    if (node is Prism4j.Syntax && end > offset) {
                        color(node.alias() ?: node.type())?.let { builder.addStyle(SpanStyle(color = it), offset, end) }
                        append(node.children(), offset)
                    }
                    offset = end
                }
                return offset
            }
            append(nodes, 0)
            builder.toAnnotatedString()
        }

    private fun color(type: String): Color? = when (type) {
        "comment", "prolog", "doctype", "cdata" -> Color(0xFF7F8EA5)
        "keyword", "atrule", "important" -> Color(0xFFC6A6EF)
        "string", "char", "attr-value", "template-string", "regex" -> Color(0xFFA6D6A2)
        "number", "boolean", "constant" -> Color(0xFFE9B782)
        "function", "function-variable" -> Color(0xFFE8D59B)
        "tag", "property", "attr-name", "selector" -> Color(0xFF8ECBE8)
        "class-name", "builtin" -> Color(0xFF7DD5C4)
        "operator", "entity", "url" -> Color(0xFFBCD6EC)
        "punctuation" -> Color(0xFFACB8CC)
        else -> null
    }
}
