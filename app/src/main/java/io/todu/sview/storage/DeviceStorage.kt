package io.todu.sview.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/** device:/ 使用主共享存储；系统保护的目录仍由 Android 拒绝访问。 */
object DeviceStorage {
    fun allowed(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** 先检查授权和挂载状态，避免把设备路径误写入项目。 */
    @Suppress("DEPRECATION")
    fun root(context: Context): File {
        io.todu.sview.runtime.api.checkPermission(allowed(context)) { "请先在设置 → 权限中允许设备存储访问" }
        check(Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) { "设备存储不可写" }
        return Environment.getExternalStorageDirectory()
    }
}
