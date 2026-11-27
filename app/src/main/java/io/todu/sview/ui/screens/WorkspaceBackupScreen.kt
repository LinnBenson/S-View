package io.todu.sview.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.todu.sview.data.WorkspaceModel
import io.todu.sview.ui.components.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 选好文件后再次确认覆盖范围，取消选择不会修改工作区。 */
@Composable
fun WorkspaceBackupScreen(model: WorkspaceModel, onBack: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        it?.let { uri -> model.transferBackup(uri, restore = false) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        selected = it?.toString()
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("数据备份还原", onBack)
        Column(Modifier.widthIn(max = 648.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 24.dp)) {
            SettingRow("upload", "备份到文件") {
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                create.launch("S-View-备份-$stamp.zip")
            }
            HorizontalDivider(color = Line)
            SettingRow("import", "从文件还原") { open.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            Text("包含全部项目、项目分类、全局模板和共享数据库。", color = Muted, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
    selected?.let { uri ->
        AppDialog(onDismissRequest = { selected = null }, title = { Text("还原备份？") },
            text = { Text("备份中的项目、分类、全局模板和共享数据库将替换手机上的现有数据。") },
            dismissButton = { DialogAction("取消", { selected = null }) },
            confirmButton = { DialogAction("还原", { selected = null; model.transferBackup(Uri.parse(uri), restore = true) }, primary = true) })
    }
}
