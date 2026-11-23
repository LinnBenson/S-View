package io.todu.sview.ui.screens

import io.todu.sview.BuildConfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.sview.ui.components.*

/** 固定外观与编辑器样式，只保留实际需要管理的设置入口。 */
@Composable
fun SettingsScreen(wide: Boolean, onBack: () -> Unit, onTemplates: () -> Unit, onPermissions: () -> Unit, onLogs: () -> Unit, onInterfaces: () -> Unit, clearingCache: Boolean, onClearCache: (() -> Unit) -> Unit, onLanEditor: () -> Unit, lanStatus: String, lanRunning: Boolean, onDatabase: () -> Unit, onWatch: () -> Unit, onAbout: () -> Unit, onHelp: () -> Unit, onFtpEditor: () -> Unit, ftpStatus: String, ftpRunning: Boolean) {
    var confirmClear by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("设置", if (wide) null else onBack)
        Column(Modifier.widthIn(max = 648.dp).fillMaxWidth().align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(4.dp))
            Text("工作区", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
            SettingRow("database", "数据库", onClick = onDatabase)
            HorizontalDivider(color = Line)
            SettingRow("file-code", "模板文件", onClick = onTemplates)
            HorizontalDivider(color = Line)
            SettingRow("shield", "权限", onClick = onPermissions)
            HorizontalDivider(color = Line)
            SettingRow("code", "系统接口", onClick = onInterfaces)
            HorizontalDivider(color = Line)
            SettingRow("circle-help", "使用帮助", onClick = onHelp)
            HorizontalDivider(color = Line)
            SettingRow("file-spreadsheet", "项目日志", onClick = onLogs)
            Spacer(Modifier.height(16.dp))
            Text("连接", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
            SettingRow("watch", "手表连接", onClick = onWatch)
            HorizontalDivider(color = Line)
            SettingRow("network", "局域网编辑器", value = lanStatus, onClick = onLanEditor,
                valueColor = if (lanRunning) androidx.compose.ui.graphics.Color(0xFF42856B) else Muted)
            HorizontalDivider(color = Line)
            SettingRow("folder", "FTP 编辑器", value = ftpStatus, onClick = onFtpEditor,
                valueColor = if (ftpRunning) androidx.compose.ui.graphics.Color(0xFF42856B) else Muted)
            Spacer(Modifier.height(16.dp))
            Text("通用", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
            SettingRow("trash", "清除缓存", value = if (clearingCache) "清理中…" else if (cacheCleared) "已清除" else null,
                onClick = if (clearingCache) null else ({ cacheCleared = false; confirmClear = true }))
            HorizontalDivider(color = Line)
            SettingRow("info", "关于 S View", value = BuildConfig.VERSION_NAME, onClick = onAbout)
        }
    }
    if (confirmClear) AppDialog(onDismissRequest = { confirmClear = false }, title = { Text("清除缓存") },
        text = { Text("清空所有项目的网页存储、Cookie、运行缓存和日志，并删除各项目资源根目录下的 cache 文件夹及其全部内容。其他资源、项目配置及模板文件会保留。") },
        dismissButton = { DialogAction("取消", { confirmClear = false }) },
        confirmButton = { DialogAction("清除", { confirmClear = false; onClearCache { cacheCleared = true } }, primary = true) })

}

@Composable
internal fun SettingRow(icon: String, title: String, value: String? = null, valueColor: androidx.compose.ui.graphics.Color = Muted, onClick: (() -> Unit)?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val watch = remember(context) { io.todu.sview.wear.WatchDevice.isWatch(context) }
    if (watch) {
        androidx.wear.compose.material.Chip(
            onClick = { onClick?.invoke() }, modifier = Modifier.fillMaxWidth(),
            colors = androidx.wear.compose.material.ChipDefaults.secondaryChipColors(),
            label = { Text(title, fontSize = 13.sp, lineHeight = 16.sp) },
            secondaryLabel = value?.let { { Text(it, color = valueColor, fontSize = 11.sp, lineHeight = 14.sp) } },
            icon = { AppIcon(icon, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)) })
        return
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon)
        Text(title, Modifier.weight(1f).padding(start = 16.dp), fontSize = 15.sp)
        value?.let { Text(it, color = valueColor, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp)) }
        if (onClick != null) AppIcon("chevron-right", tint = Muted, modifier = Modifier.size(18.dp))
    }
}

/** 权限设置独立展示，网络为安装时授予的普通权限，不提供无效的开关。 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("权限", onBack)
        Column(Modifier.widthIn(max = 648.dp).fillMaxWidth().align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(16.dp))
            SettingRow("globe", "网络访问", value = "已允许", onClick = null)
            HorizontalDivider(color = Line)
            NotificationPermissionRow()
            DevicePermissionRows()
            HorizontalDivider(color = Line)
            HealthPermissionRow()
        }
    }
}
