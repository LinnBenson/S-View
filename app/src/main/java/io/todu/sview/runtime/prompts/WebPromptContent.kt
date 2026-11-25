package io.todu.sview.runtime.prompts

import androidx.compose.foundation.isSystemInDarkTheme
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mozilla.geckoview.GeckoSession.PromptDelegate.*

/** 提示内容按类型拆分，所有确认均使用当前内核请求，不执行网页提供的字符串。 */
@Composable
internal fun WebPromptContent(request: WebPromptRequest) {
    val context = LocalContext.current
    val watch = io.todu.sview.wear.WatchDevice.isWatch(context)
    val prompt = request.prompt
    if (!watch && (prompt is AlertPrompt || prompt is ButtonPrompt || prompt is TextPrompt)) {
        // 三种消息复用应用固定色板，不额外生成一套不会使用的系统动态配色。
        io.todu.sview.ui.theme.SViewTheme { MessageContent(request, prompt) }
        return
    }
    val dark = isSystemInDarkTheme()
    val colors = if (watch) darkColorScheme(
        primary = Color(0xFFA8C2FF), onPrimary = Color(0xFF172C55),
        surface = Color.Black, onSurface = Color(0xFFF1F3F8),
        surfaceVariant = Color(0xFF252A32), onSurfaceVariant = Color(0xFFC0C7D3),
        secondaryContainer = Color(0xFF253650), onSecondaryContainer = Color(0xFFD6E3FF),
    ) else if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else MaterialTheme.colorScheme
    val typography = if (watch) MaterialTheme.typography.copy(
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 18.sp),
        bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
    ) else MaterialTheme.typography.copy(
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
    )
    MaterialTheme(colorScheme = colors, typography = typography) {
        when (prompt) {
            is ChoicePrompt -> ChoiceContent(request, prompt)
            is ColorPrompt -> ColorContent(request, prompt)
            is DateTimePrompt -> DateTimeContent(request, prompt)
            is FilePrompt -> FileContent(request, prompt)
            else -> MessageContent(request, prompt)
        }
    }
}

internal data class PromptChoice(val choice: ChoicePrompt.Choice, val depth: Int, val disabled: Boolean)

/** 展开选项组，继承父组禁用状态；分组和分隔线不作为可选值回传。 */
internal fun flattenChoices(choices: Array<ChoicePrompt.Choice>, depth: Int = 0,
    disabled: Boolean = false): List<PromptChoice> = buildList {
    choices.forEach {
        add(PromptChoice(it, depth, disabled || it.disabled))
        it.items?.let { children -> addAll(flattenChoices(children, depth + 1, disabled || it.disabled)) }
    }
}

@Composable
private fun ChoiceContent(request: WebPromptRequest, prompt: ChoicePrompt) {
    val watch = io.todu.sview.wear.WatchDevice.isWatch(LocalContext.current)
    val entries = remember(prompt) { flattenChoices(prompt.choices) }
    val multiple = prompt.type == ChoicePrompt.Type.MULTIPLE
    // 内核更新选项时重新读取 selected，避免回传已被页面移除的选项编号。
    var selected by remember(prompt) { mutableStateOf(entries.filter { it.choice.selected }.map { it.choice.id }.toSet()) }
    PromptPanel(if (multiple) "选择多项" else "选择一项", request::cancel, {
        request.complete {
            val values = entries.filter { it.choice.id in selected && it.choice.items == null && !it.choice.separator }
                .map { it.choice.id }.toTypedArray()
            if (multiple) prompt.confirm(values) else prompt.confirm(values.first())
        }
    }, confirmVisible = multiple, enabled = multiple || entries.any { it.choice.id in selected && it.choice.items == null && !it.choice.separator }) {
        prompt.message?.takeIf { it.isNotBlank() }?.let { message -> item { Text(message) } }
        items(entries) { entry ->
            val choice = entry.choice
            when {
                choice.separator -> HorizontalDivider()
                choice.items != null -> Text(choice.label.orEmpty(), color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = (entry.depth * 10).dp))
                else -> Row(Modifier.fillMaxWidth().heightIn(min = if (watch) 48.dp else 56.dp)
                    .clip(RoundedCornerShape(if (watch) 24.dp else 12.dp))
                    .background(if (choice.id in selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .05f))
                    .toggleable(choice.id in selected, enabled = !entry.disabled,
                        role = if (multiple) Role.Checkbox else Role.RadioButton) {
                        if (!multiple) request.complete { prompt.confirm(choice.id) }
                        else selected = if (choice.id in selected) selected - choice.id else selected + choice.id
                    }.padding(start = (8 + entry.depth * 10).dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(choice.label.orEmpty(), modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (entry.disabled) .35f else 1f))
                    if (multiple) Checkbox(choice.id in selected, onCheckedChange = null, enabled = !entry.disabled,
                        modifier = Modifier.padding(end = 12.dp).size(22.dp))
                    else RadioButton(choice.id in selected, onClick = null, enabled = !entry.disabled,
                        modifier = Modifier.padding(end = 12.dp).size(22.dp))
                }
            }
        }
    }
}
