package io.todu.sview.runtime.prompts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.ui.components.AppIcon
import io.todu.sview.wear.WatchDevice

/** 不主动移动焦点：Gecko 的 select、month 和 week 会在网页元素失焦时撤销提示。 */
@Composable
internal fun PromptPanel(title: String, cancel: () -> Unit, confirm: () -> Unit,
    enabled: Boolean = true, cancelVisible: Boolean = true, confirmVisible: Boolean = true,
    content: LazyListScope.() -> Unit) {
    PromptOverlay {
        if (WatchDevice.isWatch(LocalContext.current)) {
            WatchPromptPanel(title, cancel, confirm, enabled, cancelVisible, confirmVisible, content)
        } else {
            PhonePromptPanel(title, cancel, confirm, enabled, cancelVisible, confirmVisible, content)
        }
    }
}

/** 遮罩只拦截触摸；确认、取消与系统返回负责结束请求。 */
@Composable
internal fun PromptOverlay(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .48f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}))
        content()
    }
}

/** 手表使用全屏黑底，只有内容留出圆弧安全区，不给表盘额外套一层卡片。 */
@Composable
private fun WatchPromptPanel(title: String, cancel: () -> Unit, confirm: () -> Unit,
    enabled: Boolean, cancelVisible: Boolean, confirmVisible: Boolean, content: LazyListScope.() -> Unit) {
    val round = LocalConfiguration.current.isScreenRound
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxSize().semantics { paneTitle = title },
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = maxWidth * (if (round) .10f else .06f),
                vertical = maxHeight * (if (round) .065f else .04f)), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, Modifier.fillMaxWidth().padding(bottom = 14.dp), fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (cancelVisible || !confirmVisible) {
                        PromptRoundAction("x", "取消", cancel, primary = false)
                    }
                    if (confirmVisible) PromptRoundAction("check", "确定", confirm, primary = true, enabled = enabled)
                }
            }
        }
    }
}

/** 使用 44dp 触摸目标和 Lucide 图标，圆屏底部不挤入两段文字。 */
@Composable
private fun PromptRoundAction(icon: String, label: String, click: () -> Unit, primary: Boolean, enabled: Boolean = true) {
    FilledIconButton(onClick = click, enabled = enabled, modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)) {
        AppIcon(icon, label, Modifier.size(20.dp), LocalContentColor.current)
    }
}

/** 窄屏采用底部面板，折叠屏展开后居中限宽；不创建新窗口，避免改变网页焦点。 */
@Composable
private fun PhonePromptPanel(title: String, cancel: () -> Unit, confirm: () -> Unit,
    enabled: Boolean, cancelVisible: Boolean, confirmVisible: Boolean, content: LazyListScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 600.dp
        val horizontal = if (wide) 24.dp else 0.dp
        Surface(Modifier.align(if (wide) Alignment.Center else Alignment.BottomCenter)
            .width(minOf((maxWidth - horizontal * 2).coerceAtLeast(0.dp), 560.dp))
            .heightIn(max = maxHeight * .9f).semantics { paneTitle = title },
            shape = if (wide) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(if (wide) 24.dp else 20.dp))
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    if (!confirmVisible) IconButton(onClick = cancel, modifier = Modifier.size(48.dp)) {
                        AppIcon("x", "取消", Modifier.size(22.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                if (confirmVisible) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        if (cancelVisible) TextButton(onClick = cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                        Button(onClick = confirm, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 32.dp)) { Text("确定") }
                    }
                } else Spacer(Modifier.height(12.dp))
            }
        }
    }
}
