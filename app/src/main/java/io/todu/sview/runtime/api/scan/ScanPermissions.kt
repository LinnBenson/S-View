package io.todu.sview.runtime.api.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat

/** 扫描结果包含无线设备标识，不声明 neverForLocation，统一要求精确位置授权。 */
internal object ScanPermissions {
    fun require(context: Context, permission: String, message: String) {
        io.todu.sview.runtime.api.checkPermission(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) { message }
    }

    fun location(context: Context) {
        require(context, Manifest.permission.ACCESS_FINE_LOCATION, "请在设置 → 权限中允许精确位置")
        val manager = context.getSystemService(LocationManager::class.java)
        check(manager != null && LocationManagerCompat.isLocationEnabled(manager)) { "请开启系统位置服务" }
    }
}
