package io.todu.sview.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import io.todu.sview.R
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.ui.components.*

private const val EditorIndent = EditorAutoIndent.DEFAULT_INDENT

private val EditorFont = FontFamily(Font(R.font.jetbrains_mono_regular))
private val EditorBackground = Color(0xFF18202E)
private val EditorForeground = Color(0xFFD9E1EE)

/** 代码、模板、数组共用编辑器；历史有容量上限，语法结果只随文本变化重算。 */
@Composable
fun CodeEditor(text: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, identity: Any = Unit, fontSize: Int = 13, language: String? = "javascript") {
    var value by remember(identity) { mutableStateOf(TextFieldValue(text)) }
    val undo = remember(identity) { mutableStateListOf<TextFieldValue>() }
    val redo = remember(identity) { mutableStateListOf<TextFieldValue>() }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    var styled by remember(identity, language) { mutableStateOf(AnnotatedString(value.text)) }
    fun updateValue(next: TextFieldValue) {
        if (next.text != value.text) {
            styled = if (language != null) HighlightPreview.rebase(styled, next.text) else AnnotatedString(next.text)
        }
        value = next
    }
    LaunchedEffect(text) { if (value.text != text) updateValue(TextFieldValue(text)) }
    fun replace(next: TextFieldValue) {
        if (next.text != value.text) {
            undo.add(value)
            while (undo.size > 80 || undo.sumOf { it.text.length } > 500_000) undo.removeAt(0)
            redo.clear()
            onChange(next.text)
        }
        updateValue(next)
    }
    // 只接管输入法或实体键盘插入的单个换行，撤销、重做和粘贴沿用原内容。
    fun input(next: TextFieldValue) {
        val start = value.selection.min
        val end = value.selection.max
        val singleNewline = next.composition == null && next.selection.collapsed && next.selection.start == start + 1 &&
            next.text.length == value.text.length - (end - start) + 1 && next.text.getOrNull(start) == '\n' &&
            next.text.regionMatches(0, value.text, 0, start) &&
            next.text.regionMatches(start + 1, value.text, end, value.text.length - end)
        if (singleNewline) {
            val edit = EditorAutoIndent.newline(value.text, start, end, language)
            replace(TextFieldValue(edit.text, TextRange(edit.cursor)))
        } else replace(next)
    }
    // 软键盘快捷键与实体键盘 Tab 使用相同的四空格缩进。
    fun insertIndent() {
        val start = value.selection.min
        replace(TextFieldValue(value.text.replaceRange(start, value.selection.max, EditorIndent), TextRange(start + EditorIndent.length)))
    }
    LaunchedEffect(value.text, language, identity) {
        // 内容变化会取消上一任务；超过 10 KB 立即回到纯文本，不排队解析。
        if (language == null || !SyntaxHighlighter.canHighlight(value.text)) {
            styled = AnnotatedString(value.text)
            return@LaunchedEffect
        }
        // 短暂合并连续输入；光标移动不触发重新解析。
        val input = value.text
        delay(90)
        val result = SyntaxHighlighter.highlight(input, language)
        // 输入到下一次重组之间仍可能收到旧结果，不能覆盖已平移的样式。
        if (value.text == input) styled = result
    }
    // 在组合阶段读取样式快照；只在转换回调中读取 State 不会使文本布局重新计算。
    val highlighted = styled
    val transformation = remember(highlighted) {
        VisualTransformation { input ->
            TransformedText(if (highlighted.text == input.text) highlighted else input, OffsetMapping.Identity)
        }
    }
    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val lineLabels = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val style = TextStyle(color = EditorForeground, fontSize = fontSize.sp, lineHeight = 25.sp, fontFamily = EditorFont)
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().background(EditorBackground)) {
            Row(Modifier.fillMaxSize().verticalScroll(vertical).padding(vertical = 16.dp)) {
                Text(lineLabels, Modifier.width(40.dp).padding(end = 12.dp), color = Color(0xFF95A2B8),
                    style = style.copy(fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End))
                Box(Modifier.weight(1f).horizontalScroll(horizontal)) {
                    BasicTextField(value, ::input, Modifier.widthIn(min = 300.dp).padding(end = 20.dp).fillMaxHeight().onPreviewKeyEvent {
                        if (it.key == Key.Tab && it.type == KeyEventType.KeyDown && !it.isShiftPressed && !it.isCtrlPressed && !it.isAltPressed) {
                            insertIndent()
                            true
                        } else false
                    },
                        textStyle = style, cursorBrush = SolidColor(Blue),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                        visualTransformation = transformation)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(EditorBackground).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("UTF-8", color = Color(0xFF95A2B8), fontSize = 10.sp)
            val prefix = value.text.take(value.selection.end.coerceIn(0, value.text.length))
            Text("行 ${prefix.count { it == '\n' } + 1}，列 ${prefix.substringAfterLast('\n').length + 1}", color = Color(0xFF95A2B8), fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth().height(48.dp).background(Color.White).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            IconAction("undo-2", "撤销", {
                if (undo.isNotEmpty()) { redo.add(value); updateValue(undo.removeAt(undo.lastIndex)); onChange(value.text) }
            }, enabled = undo.isNotEmpty())
            KeyDivider()
            IconAction("redo-2", "重做", {
                if (redo.isNotEmpty()) { undo.add(value); updateValue(redo.removeAt(redo.lastIndex)); onChange(value.text) }
            }, enabled = redo.isNotEmpty())
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 10.dp), color = Color(0xFFCDD3DF))
            IconAction("indent-increase", "缩进", ::insertIndent)
            listOf("''", "\"\"", "``", ";", "()", "{}", "[]", "<>", ",", "/").forEach { insertion ->
                KeyDivider()
                TextButton(onClick = {
                    val start = value.selection.min; val end = value.selection.max
                    val next = value.text.replaceRange(start, end, insertion)
                    replace(TextFieldValue(next, TextRange(start + if (insertion.length == 2) 1 else insertion.length)))
                }, modifier = Modifier.width(44.dp), contentPadding = PaddingValues(0.dp)) {
                    Text(insertion, color = Color(0xFF626C7C), fontFamily = EditorFont, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun KeyDivider() { VerticalDivider(Modifier.height(16.dp), color = Line) }

