package io.todu.sview.runtime.api

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/** 读取公开设备与系统信息，不读取设备唯一标识，也不注册后台监听。 */
internal class SystemInfoReader(private val context: Context) {
    fun read(): JSONObject {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = manager?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }
        val features = context.packageManager
        val type = when {
            features.hasSystemFeature(PackageManager.FEATURE_WATCH) -> "watch"
            features.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) -> "automotive"
            features.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> "tv"
            else -> "handheld"
        }
        return JSONObject().put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND)
            .put("model", Build.MODEL).put("device", Build.DEVICE).put("product", Build.PRODUCT)
            .put("hardware", Build.HARDWARE).put("androidVersion", Build.VERSION.RELEASE)
            .put("sdkVersion", Build.VERSION.SDK_INT).put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("is64BitProcess", Process.is64Bit())
            .put("deviceType", type).put("locale", context.resources.configuration.locales[0].toLanguageTag())
            .put("timeZone", TimeZone.getDefault().id)
            .put("memory", memory?.let { JSONObject().put("total", it.totalMem).put("available", it.availMem).put("low", it.lowMemory) } ?: JSONObject.NULL)
    }
}
