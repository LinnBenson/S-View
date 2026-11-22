package io.todu.sview.runtime.api

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.todu.sview.data.ProjectRepository
import io.todu.sview.storage.ApkInstallFiles
import io.todu.sview.storage.DeviceStorage
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 复制在接口工作线程完成，主线程只检查前台状态和启动系统安装器。 */
internal class SystemPackageInstaller(private val context: Context, projectId: String) : Closeable {
    private val files = ApkInstallFiles(ProjectRepository(context).root(projectId), { DeviceStorage.root(context) }, File(context.cacheDir, "install-apks"))
    private val closed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val generation = AtomicInteger()

    fun requireAllowed() {
        checkPermission(context.packageManager.canRequestPackageInstalls()) { "请先在设置 → 权限中允许安装应用" }
    }

    /** 回调由桥接层切回主线程；正常返回只代表安装器已打开。 */
    fun install(path: String, launchOnMain: (File, () -> Unit) -> Unit) {
        check(busy.compareAndSet(false, true)) { "正在准备安装包，请稍后重试" }
        val version = generation.get()
        var prepared: File? = null
        var handedOff = false
        try {
            val active = {
                check(!closed.get() && generation.get() == version) { "安装准备已取消，请在前台重新调用" }
            }
            prepared = files.prepare(path, active)
            active()
            launchOnMain(prepared, active)
            handedOff = true
        } finally {
            if (!handedOff) prepared?.delete()
            busy.set(false)
        }
    }

    fun launch(file: File) {
        check(!closed.get()) { "运行窗口已关闭" }
        requireAllowed()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.install-files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("APK", uri)
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) { throw IllegalStateException("设备没有可用的应用安装器") }
    }

    fun background() { generation.incrementAndGet() }
    override fun close() { closed.set(true); background() }
}
