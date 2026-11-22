package io.todu.sview.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.todu.sview.runtime.api.ScriptNotifications
import io.todu.sview.ui.components.Line

/** 权限只在用户点击时申请，已申请的项目转到系统设置管理。 */
@Composable
fun NotificationPermissionRow() {
    PermissionRow("bell", "通知", "notification_requested",
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
        status = { if (ScriptNotifications.allowed(it)) "已允许" else "未允许" },
        settings = { Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName) })
}

@Composable
fun DevicePermissionRows(only: String? = null) {
    if (only == null || only == "overlay") {
        if (only == null) HorizontalDivider(color = Line)
        PermissionRow("bring-to-front", "悬浮窗", "overlay_requested", emptyArray(),
            status = { if (Settings.canDrawOverlays(it)) "已允许" else "未允许" },
            settings = { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${it.packageName}")) })
    }
    if (only == null || only == "install") {
        if (only == null) HorizontalDivider(color = Line)
        PermissionRow("download", "安装应用", "install_requested", emptyArray(),
            status = { if (it.packageManager.canRequestPackageInstalls()) "已允许" else "未允许" },
            settings = { Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${it.packageName}")) })
    }
    if (only == null || only == "storage") {
        if (only == null) HorizontalDivider(color = Line)
        PermissionRow("hard-drive", "设备存储", "storage_requested",
            if (Build.VERSION.SDK_INT < 30) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyArray(),
            status = { if (io.todu.sview.storage.DeviceStorage.allowed(it)) "已允许" else "未允许" },
            settings = { context ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    if (intent.resolveActivity(context.packageManager) != null) intent
                    else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            })
    }
    if (only == null || only == "bluetooth") {
        if (only == null) HorizontalDivider(color = Line)
        PermissionRow("bluetooth", "附近设备", "bluetooth_scan_requested",
            if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else emptyArray(),
            status = { if (Build.VERSION.SDK_INT < 31 || (granted(it, Manifest.permission.BLUETOOTH_CONNECT) && granted(it, Manifest.permission.BLUETOOTH_SCAN))) "已允许" else "未允许" })
    }
    if ((only == null || only == "wifi") && Build.VERSION.SDK_INT >= 33) {
        if (only == null) HorizontalDivider(color = Line)
        PermissionRow("wifi", "附近 Wi-Fi 设备", "nearby_wifi_requested", arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            status = { if (granted(it, Manifest.permission.NEARBY_WIFI_DEVICES)) "已允许" else "未允许" })
    }
    if (only == null || only == "location") {
        if (only == null) HorizontalDivider(color = Line)
        PermissionRow("map-pin", "位置", "location_requested", arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            status = { when {
                granted(it, Manifest.permission.ACCESS_FINE_LOCATION) -> "精确位置"
                granted(it, Manifest.permission.ACCESS_COARSE_LOCATION) -> "大致位置"
                else -> "未允许"
            } })
    }

}

private fun granted(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@Composable
internal fun PermissionRow(icon: String, title: String, key: String, permissions: Array<String>, status: (Context) -> String,
    settings: (Context) -> Intent = { Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${it.packageName}")) }) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var value by remember { mutableStateOf(status(context)) }
    val preferences = remember { context.getSharedPreferences("permissions", Context.MODE_PRIVATE) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { value = status(context) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) value = status(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SettingRow(icon, title, value) {
        if (permissions.any { !granted(context, it) } && !preferences.getBoolean(key, false)) {
            preferences.edit().putBoolean(key, true).apply()
            request.launch(permissions)
        } else context.startActivity(settings(context))
    }
}
