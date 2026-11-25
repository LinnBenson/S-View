package io.todu.sview.runtime.prompts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.todu.sview.data.ResourceFile
import io.todu.sview.runtime.WebMime
import io.todu.sview.ui.components.AppIcon
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import java.io.File

/** 在当前弹层浏览本地文件；权限不足只显示提示，不主动跳转授权或系统选择器。 */
@Composable
internal fun FileContent(request: WebPromptRequest, prompt: FilePrompt) {
    val context = LocalContext.current
    val multiple = prompt.type == FilePrompt.Type.MULTIPLE
    var device by remember { mutableStateOf(true) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<ResourceFile>()) }
    var selected by remember { mutableStateOf(emptyMap<String, ResourceFile>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path, device) {
        val directoryPath = path
        val fromDevice = device
        loading = true
        error = null
        entries = emptyList()
        try {
            entries = loadPromptFiles(context, request, fromDevice, directoryPath, prompt.mimeTypes.orEmpty())
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "无法读取目录" }
        loading = false
    }
    PromptPanel(if (multiple) "文件（${selected.size}）" else "选择文件", request::cancel, {
        if (!submitting && selected.isNotEmpty()) {
            submitting = true
            val selection = selected.values.toList()
            val fromDevice = device
            scope.launch {
                try {
                    val uris = resolvePromptFiles(context, request, fromDevice, selection)
                    request.complete { prompt.confirm(context, uris) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "无法读取所选文件" }
                finally { submitting = false }
            }
        }
    }, enabled = selected.isNotEmpty() && !loading && !submitting) {
        item {
            PromptTabs(listOf("设备", "项目"), if (device) 0 else 1, enabled = !submitting, compact = true) { index ->
                if (device != (index == 0)) { device = index == 0; path = ""; selected = emptyMap() }
            }
        }
        if (path.isNotEmpty()) item {
            TextButton(enabled = !submitting, onClick = { path = path.substringBeforeLast('/', "") }) { AppIcon("chevron-left", "上一级", Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (submitting) item { Text("正在确认…") }
        if (loading) item { Text("正在读取…") }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (!loading && error == null && entries.isEmpty()) item { Text("没有可选文件") }
        items(entries, key = { it.path }) { entry ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (entry.path in selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
                .toggleable(entry.path in selected, enabled = !submitting && !loading, role = if (entry.directory) Role.Button else if (multiple) Role.Checkbox else Role.RadioButton) {
                if (entry.directory) path = entry.path
                else selected = if (!multiple) mapOf(entry.path to entry)
                    else if (entry.path in selected) selected - entry.path else selected + (entry.path to entry)
            }.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(if (entry.directory) "folder" else "file", modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Text(entry.path.substringAfterLast('/'), Modifier.weight(1f).padding(start = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.directory) AppIcon("chevron-right", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else androidx.compose.material3.Checkbox(entry.path in selected, onCheckedChange = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** accept 是文件选择提示：支持扩展名、完整 MIME 和图片等通配类型。 */
internal fun acceptsFile(path: String, accepted: Array<out String>): Boolean {
    if (accepted.isEmpty()) return true
    val mime = WebMime.of(File(path)).substringBefore(';').lowercase()
    return accepted.any { raw ->
        val value = raw.trim().lowercase()
        value.isEmpty() || value == "*/*" ||
            (value.startsWith('.') && path.lowercase().endsWith(value)) ||
            value == mime || (value.endsWith("/*") && mime.startsWith(value.substringBefore('/') + "/"))
    }
}
