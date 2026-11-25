package io.todu.sview.runtime.prompts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import io.todu.sview.ui.components.AppIcon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.todu.sview.ui.components.AppTextField
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ColorPrompt

/** RGB 滑条与十六进制输入共享草稿，取消时不改变网页颜色。 */
@Composable
internal fun ColorContent(request: WebPromptRequest, prompt: ColorPrompt) {
    val watch = io.todu.sview.wear.WatchDevice.isWatch(LocalContext.current)
    val columns = if (watch) 3 else 6
    var hex by remember(request.id) { mutableStateOf(prompt.defaultValue?.takeIf { colorValue(it) != null } ?: "#000000") }
    var precise by remember { mutableStateOf(false) }
    val value = colorValue(hex)
    val preview = value ?: 0
    val presets = remember(prompt) {
        (prompt.predefinedValues?.toList().orEmpty() + listOf("#000000", "#ffffff", "#3869e8", "#ef4444", "#22c55e", "#f59e0b"))
            .filter { colorValue(it) != null }.distinct().take(18)
    }
    PromptPanel("选择颜色", request::cancel, { request.complete { prompt.confirm(hex.lowercase()) } }, enabled = value != null) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(24.dp).background(Color(0xff000000.toInt() or preview), RoundedCornerShape(8.dp)))
                Text(hex.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        presets.chunked(columns).forEach { row -> item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { color ->
                    val swatch = Color(0xff000000.toInt() or colorValue(color)!!)
                    val selected = hex.equals(color, true)
                    Box(Modifier.weight(1f).height(48.dp).clip(CircleShape)
                        .clickable(onClickLabel = color) { hex = color }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (watch) 36.dp else 40.dp).background(swatch, CircleShape)
                            .border(if (selected) 2.dp else 1.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) .8f else .18f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            if (selected) AppIcon("check", "已选", Modifier.size(20.dp),
                                if (swatch.luminance() > .45f) Color.Black else Color.White)
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        } }
        item { TextButton(onClick = { precise = !precise }) { Text(if (precise) "收起调整" else "精确调整") } }
        if (precise) {
            item { AppTextField(hex, { hex = it }, label = { Text("颜色值 #RRGGBB") }, singleLine = true,
                containerColor = MaterialTheme.colorScheme.surfaceVariant, textColor = MaterialTheme.colorScheme.onSurface,
                outlineColor = MaterialTheme.colorScheme.surfaceVariant) }
            items(3) { channel ->
                val shift = (2 - channel) * 8
                val component = (preview shr shift) and 255
                Text("${listOf("红", "绿", "蓝")[channel]} $component")
                Slider(component.toFloat(), { next ->
                    val rgb = (preview and (255 shl shift).inv()) or (next.toInt() shl shift)
                    hex = "#%06x".format(rgb)
                }, valueRange = 0f..255f)
            }
        }
    }
}

private val hexColorPattern = Regex("#[0-9a-fA-F]{6}")

internal fun colorValue(value: String): Int? =
    if (hexColorPattern.matches(value)) value.substring(1).toIntOrNull(16) else null
