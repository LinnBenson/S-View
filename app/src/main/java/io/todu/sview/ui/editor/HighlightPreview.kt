package io.todu.sview.ui.editor

import androidx.compose.ui.text.AnnotatedString

/** 解析新文本期间沿用未改动区间的样式，避免每次输入都让全文退回纯文本。 */
internal object HighlightPreview {
    fun rebase(previous: AnnotatedString, text: String): AnnotatedString {
        if (previous.text == text) return previous
        // 大文件立即停止高亮，也不为旧的大文件执行差异扫描。
        if (!SyntaxHighlighter.canHighlight(text) || !SyntaxHighlighter.canHighlight(previous.text)) {
            return AnnotatedString(text)
        }
        val old = previous.text
        var prefix = 0
        while (prefix < minOf(old.length, text.length) && old[prefix] == text[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(old.length, text.length) - prefix &&
            old[old.lastIndex - suffix] == text[text.lastIndex - suffix]) suffix++
        val oldEnd = old.length - suffix
        val newEnd = text.length - suffix
        val delta = text.length - old.length
        val builder = AnnotatedString.Builder(text)
        for (range in previous.spanStyles) {
            // 修改区间本身交给后台解析，只平移或裁剪已知未改变的样式。
            val leftEnd = minOf(range.end, prefix)
            if (range.start < leftEnd) builder.addStyle(range.item, range.start, leftEnd)
            val rightStart = maxOf(range.start, oldEnd)
            if (rightStart < range.end) builder.addStyle(range.item, rightStart + delta, range.end + delta)
        }
        return builder.toAnnotatedString()
    }
}
