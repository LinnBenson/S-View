package io.todu.sview.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.todu.sview.runtime.health.PhoneHealthReader
import io.todu.sview.runtime.health.WatchHealthReader
import io.todu.sview.wear.WatchDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 两端在同一权限入口授权，只申请本机数据所需的读取权限。 */
@Composable
internal fun HealthPermissionRow() {
    val context = LocalContext.current
    if (WatchDevice.isWatch(context)) {
        if (Build.VERSION.SDK_INT < 30) {
            SettingRow("heart", "健康数据", "不支持", onClick = null)
        } else {
            val permissions = WatchHealthReader.permissions()
            PermissionRow("heart", "健康数据", "health_requested", permissions, status = { device ->
                when (permissions.count { device.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                    0 -> "未允许"
                    permissions.size -> "已允许"
                    else -> "部分允许"
                }
            })
        }
        return
    }
    var refresh by remember { mutableIntStateOf(0) }
    var sdk by remember { mutableIntStateOf(HealthConnectClient.SDK_UNAVAILABLE) }
    var value by remember { mutableStateOf("读取中") }
    val preferences = remember { context.getSharedPreferences("permissions", android.content.Context.MODE_PRIVATE) }
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh++ }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        val state = withContext(Dispatchers.IO) {
            val available = HealthConnectClient.getSdkStatus(context)
            val status = when (available) {
                HealthConnectClient.SDK_UNAVAILABLE -> "不支持"
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "需要安装或更新"
                else -> runCatching {
                    val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
                    when (PhoneHealthReader.permissions.count { it in granted }) {
                        0 -> "未允许"
                        PhoneHealthReader.permissions.size -> "已允许"
                        else -> "部分允许"
                    }
                }.getOrDefault("暂不可用")
            }
            available to status
        }
        sdk = state.first
        value = state.second
    }
    SettingRow("heart", "健康数据", value, onClick = if (sdk == HealthConnectClient.SDK_UNAVAILABLE) null else {
        {
            try {
                if (sdk == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                } else if (!preferences.getBoolean("health_connect_requested", false)) {
                    preferences.edit().putBoolean("health_connect_requested", true).apply()
                    request.launch(PhoneHealthReader.permissions)
                } else context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(context, "无法打开健康数据设置", Toast.LENGTH_SHORT).show()
            }
        }
    })
}
