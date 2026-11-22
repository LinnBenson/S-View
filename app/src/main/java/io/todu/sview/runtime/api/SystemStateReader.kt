package io.todu.sview.runtime.api

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject

/** 状态接口仅取当前快照，不注册网络、蓝牙或电量监听。 */
internal class SystemStateReader(private val context: Context) {
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)

    fun network(): JSONObject {
        val manager = connectivity
        val network = manager.activeNetwork
        val caps = network?.let(manager::getNetworkCapabilities)
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
        return JSONObject().put("connected", caps != null).put("type", type)
            .put("internetAvailable", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("metered", caps != null && manager.isActiveNetworkMetered)
    }

    fun wifi(): JSONObject {
        val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
        val manager = context.getSystemService(WifiManager::class.java)
        val raw = if (supported) manager?.wifiState else null
        val state = when (raw) {
            WifiManager.WIFI_STATE_ENABLED -> "enabled"
            WifiManager.WIFI_STATE_DISABLED -> "disabled"
            WifiManager.WIFI_STATE_ENABLING -> "enabling"
            WifiManager.WIFI_STATE_DISABLING -> "disabling"
            else -> if (supported) "unknown" else "unavailable"
        }
        val caps = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        return JSONObject().put("supported", supported).put("enabled", raw == WifiManager.WIFI_STATE_ENABLED)
            .put("state", state).put("connected", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
    }

    fun bluetooth(): JSONObject {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) return JSONObject().put("supported", false).put("enabled", false).put("state", "unavailable")
        if (Build.VERSION.SDK_INT >= 31) io.todu.sview.runtime.api.checkPermission(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            "请在设置 → 权限中允许附近设备"
        }
        val state = when (adapter.state) {
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_OFF -> "off"
            BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
            BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
            else -> "unknown"
        }
        return JSONObject().put("supported", true).put("enabled", state == "on").put("state", state)
    }

    fun battery(): JSONObject {
        val intent = checkNotNull(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))) { "无法读取电量信息" }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val source = when (plugged) {
            0 -> "battery"
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            8 -> "dock"
            else -> "unknown"
        }
        return JSONObject().put("level", if (level >= 0 && scale > 0) (level * 100.0 / scale).toInt().coerceIn(0, 100) else JSONObject.NULL)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("powerSource", source).put("powerSaveMode", context.getSystemService(PowerManager::class.java).isPowerSaveMode)
    }
}
