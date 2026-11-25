package io.todu.sview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 自定义弹窗只借用系统窗口承载，尺寸、表单和操作区统一由应用布局。 */
@Composable
fun AppDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: (@Composable () -> Unit)? = null, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null, maxWidth: Dp = 440.dp) {
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AppDialogContent(onDismissRequest, title, text, confirmButton, dismissButton, maxWidth)
    }
}

/** 弹窗内容与系统窗口分开，网页提示可在原界面复用同一套布局，不抢走网页焦点。 */
@Composable
fun AppDialogContent(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: (@Composable () -> Unit)? = null, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null, maxWidth: Dp = 440.dp, modifier: Modifier = Modifier) {
    val widthLimit = maxWidth
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.width(minOf(this.maxWidth, widthLimit)).heightIn(max = maxHeight)
            .clip(RoundedCornerShape(20.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(20.dp))) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ProvideTextStyle(TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium), title) }
                IconAction("x", "关闭弹窗", onDismissRequest)
            }
            if (text != null) Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                ProvideTextStyle(TextStyle(color = Ink, fontSize = 14.sp, lineHeight = 22.sp), text)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

/** 标签与输入区分开，不使用系统浮动标签，键盘展开后仍可滚动到操作区。 */
@Composable
fun AppTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null, singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, enabled: Boolean = true,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    containerColor: Color = Panel, textColor: Color = Ink, outlineColor: Color = Line) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (label != null) CompositionLocalProvider(LocalContentColor provides Muted) {
            ProvideTextStyle(TextStyle(fontSize = 12.sp, color = Muted), label)
        }
        BasicTextField(value, onValueChange,
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(containerColor)
                .border(1.dp, if (focused) Blue.copy(alpha = .55f) else outlineColor, RoundedCornerShape(10.dp))
                .onFocusChanged { focused = it.isFocused }.padding(horizontal = 13.dp, vertical = 12.dp),
            textStyle = TextStyle(color = textColor, fontSize = 14.sp, lineHeight = 21.sp),
            cursorBrush = SolidColor(Blue), singleLine = singleLine, keyboardOptions = keyboardOptions,
            enabled = enabled, visualTransformation = visualTransformation,
            minLines = if (singleLine) 1 else 2, maxLines = if (singleLine) 1 else 5)
    }
}

/** 操作区统一使用图标与短文字，取消弱化，保存与创建保留轻量强调。 */
@Composable
fun DialogAction(label: String, onClick: () -> Unit, primary: Boolean = false) {
    val destructive = label in setOf("删除", "清除")
    val tint = when {
        destructive -> Color(0xFFC45360)
        primary -> Blue
        else -> Muted
    }
    val icon = when (label) {
        "创建", "添加" -> "plus"
        "保存", "还原", "确定" -> "check"
        "删除", "清除" -> "trash"
        else -> "x"
    }
    val background = when {
        destructive -> Color(0xFFFCF0F2)
        primary -> Color(0xFFEAF0FF)
        else -> Color.Transparent
    }
    Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
        .background(background).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
