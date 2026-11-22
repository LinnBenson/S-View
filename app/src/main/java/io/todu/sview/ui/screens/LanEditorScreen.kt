package io.todu.sview.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.lan.LanEditorController
import io.todu.sview.ui.components.*
import kotlinx.coroutines.launch

/** 连接页沿用应用表单与浅色操作区，服务状态和访问地址分开展示。 */
@Composable
fun LanEditorScreen(controller: LanEditorController, onBack: () -> Unit) {
    var port by remember { mutableStateOf(controller.savedPort) }
    // 密码仅在本页内存中使用，不进入可恢复状态或剪贴板。
    var password by remember { mutableStateOf("") }
    var passwordEdited by remember { mutableStateOf(false) }
    LaunchedEffect(controller) {
        val saved = controller.savedPassword()
        if (!passwordEdited) password = saved
    }
    var showPassword by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val round = LocalConfiguration.current.isScreenRound
    val editable = !controller.running && !controller.busy
    Column(Modifier.fillMaxSize()) {
        PageHeader("局域网编辑器", onBack)
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
            .imePadding().verticalScroll(rememberScrollState()).padding(horizontal = if (round) 4.dp else 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            LanServiceStatus(controller)
            if (controller.running) LanAddresses(controller.addresses)
            else Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                AppTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("端口") },
                    enabled = editable, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                AppTextField(password, { passwordEdited = true; password = it.take(128) }, label = {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("访问密码")
                        Spacer(Modifier.weight(1f))
                        Text(if (showPassword) "隐藏" else "显示", color = Blue,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) { showPassword = !showPassword }
                                .padding(horizontal = 6.dp, vertical = 6.dp))
                    }
                }, enabled = editable, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (editable) Text("至少 4 位", color = Muted, fontSize = 11.sp)
            }
            LanServiceAction(controller) {
                if (controller.running) controller.stop() else scope.launch { controller.start(port, password) }
            }
            controller.message?.let { Text(it, color = Color(0xFFC45360), fontSize = 12.sp, lineHeight = 19.sp) }
            Text("同一局域网 · 离开应用自动停止\nHTTP 连接，仅在可信网络使用", color = Muted, fontSize = 11.sp, lineHeight = 19.sp)
        }
    }
}

/** 状态从服务控制器直接读取，不在页面维护另一份运行标记。 */
@Composable
private fun LanServiceStatus(controller: LanEditorController) {
    val tint = if (controller.running) Color(0xFF42856B) else if (controller.busy) Blue else Muted
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            AppIcon("network", tint = tint, modifier = Modifier.size(23.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("局域网服务", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(tint, CircleShape))
                Text(controller.statusText, color = tint, fontSize = 12.sp)
            }
        }
    }
}

/** 启停使用与项目弹窗一致的圆角、图标和浅色强调。 */
@Composable
private fun LanServiceAction(controller: LanEditorController, onClick: () -> Unit) {
    val tint = if (controller.running) Color(0xFFC45360) else Blue
    val background = if (controller.running) Color(0xFFFCF0F2) else Color(0xFFEAF0FF)
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(10.dp)).background(background)
        .clickable(enabled = !controller.busy, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        if (controller.busy) CircularProgressIndicator(Modifier.size(16.dp), color = tint, strokeWidth = 1.5.dp)
        else AppIcon(if (controller.running) "square" else "play", tint = tint, modifier = Modifier.size(16.dp))
        Text(if (controller.busy) "启动中…" else if (controller.running) "停止服务" else "启动服务", color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** 地址允许选择和一键复制，长地址在窄屏内换行。 */
@Composable
private fun LanAddresses(addresses: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("访问地址", color = Muted, fontSize = 12.sp)
        addresses.forEach { address ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).padding(start = 13.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) { Text(address, color = Blue, fontSize = 13.sp, lineHeight = 20.sp) }
                CopyAction(address, "复制访问地址")
            }
        }
    }
}
