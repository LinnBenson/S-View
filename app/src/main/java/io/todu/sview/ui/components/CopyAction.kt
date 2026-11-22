package io.todu.sview.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 列表和帮助详情共用复制行为，保留完整文本与原有换行。 */
@Composable
fun CopyAction(text: String, label: String, tint: Color = Muted) {
    val context = LocalContext.current
    IconAction("copy", label, {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }, tint = tint, iconSize = 18.dp)
}
