package io.todu.sview.runtime.prompts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.todu.sview.ui.components.AppDialogContent
import io.todu.sview.ui.components.AppTextField
import io.todu.sview.ui.components.DialogAction
import io.todu.sview.wear.WatchDevice
import org.mozilla.geckoview.GeckoSession.PromptDelegate.*

/** 三种 JS 消息共用确认出口，手机沿用应用弹窗组件，手表保持全屏操作布局。 */
@Composable
internal fun MessageContent(request: WebPromptRequest, prompt: BasePrompt) {
    val message = when (prompt) {
        is AlertPrompt -> prompt.message
        is ButtonPrompt -> prompt.message
        is TextPrompt -> prompt.message
        else -> ""
    }.orEmpty()
    val title = if (prompt is TextPrompt) "输入" else if (prompt is ButtonPrompt) "确认" else "提示"
    var text by rememberSaveable(request.id) { mutableStateOf((prompt as? TextPrompt)?.defaultValue.orEmpty()) }
    val confirm: () -> Unit = {
        request.complete {
            when (prompt) {
                is TextPrompt -> prompt.confirm(text)
                is ButtonPrompt -> prompt.confirm(ButtonPrompt.Type.POSITIVE)
                else -> prompt.dismiss()
            }
        }
    }
    if (WatchDevice.isWatch(LocalContext.current)) {
        PromptPanel(title, request::cancel, confirm, cancelVisible = prompt !is AlertPrompt) {
            item { Text(message) }
            if (prompt is TextPrompt) item {
                AppTextField(text, { text = it }, singleLine = true,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant, textColor = MaterialTheme.colorScheme.onSurface,
                    outlineColor = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    } else {
        PromptOverlay {
            AppDialogContent(onDismissRequest = request::cancel, title = { Text(title) },
                modifier = Modifier.semantics { paneTitle = title }, text = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (message.isNotEmpty()) Text(message)
                        if (prompt is TextPrompt) AppTextField(text, { text = it }, singleLine = true)
                    }
                }, dismissButton = if (prompt is AlertPrompt) null else {
                    { DialogAction("取消", request::cancel) }
                }, confirmButton = { DialogAction("确定", confirm, primary = true) })
        }
    }
}
