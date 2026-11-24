package io.todu.sview.runtime.api

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Vibrator
import android.provider.Settings
import io.todu.sview.device.DeviceVibration
import org.json.JSONObject

/** 按需读取设备设置，不注册监听或轮询；振动只执行有时长上限的单次效果。 */
internal class SystemDeviceActions(private val context: Context) {
    private var vibrating: Vibrator? = null

    private fun vibrator(): Vibrator = DeviceVibration.vibrator(context)

    fun cancelVibration() { vibrator().cancel(); vibrating = null }

    fun vibrate(duration: Long): Boolean {
        val vibrator = vibrator()
        if (!vibrator.hasVibrator()) return false
        vibrator.vibrate(DeviceVibration.effect(duration))
        vibrating = vibrator
        return true
    }

    fun readClipboard(): Any {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return JSONObject.NULL
        if (clip.itemCount == 0) return JSONObject.NULL
        // 只读取已有文字，不把 URI 转换为内容，避免隐式访问其他应用的资源。
        val value = clip.getItemAt(0).text ?: return JSONObject.NULL
        require(value.length <= 65536) { "剪切板文字超过 65536 个字符" }
        return value.toString()
    }

    fun writeClipboard(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("S View", text))
        // 只在写入成功后反馈；振动不可用不影响已经完成的复制。
        runCatching { vibrate(20L) }
    }

    fun rotationSettings(): JSONObject {
        val resolver = context.contentResolver
        val automatic = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION)
        val rotation = Settings.System.getString(resolver, Settings.System.USER_ROTATION)?.toIntOrNull()
        return JSONObject().put("autoRotate", automatic != 0)
            .put("lockedRotation", if (rotation != null && rotation in 0..3) rotation * 90 else JSONObject.NULL)
    }

    fun close() { vibrating?.cancel(); vibrating = null }
}
