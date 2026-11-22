package io.todu.sview.runtime.api

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.todu.sview.data.ProjectRepository
import io.todu.sview.runtime.storage.StoragePaths
import io.todu.sview.storage.DeviceStorage
import org.json.JSONObject
import java.io.Closeable
import io.todu.sview.storage.DeviceResourceMime

/** 图片沿用应用内预览，其他文件只向系统接收应用授予该文件的临时读取权限。 */
internal class DeviceLinkApiBridge(private val context: Context, projectId: String,
    private val foreground: () -> Boolean, private val preview: (String) -> Unit) : Closeable {
    private val paths by lazy { StoragePaths(ProjectRepository(context).root(projectId)) { DeviceStorage.root(context) } }
    private val main = MainThreadCalls()

    fun invoke(request: JSONObject): JSONObject = try {
        val path = request.getJSONObject("args").getString("path")
        require(path.startsWith("device:/") && path.length <= 4096) { "设备路径无效" }
        val file = paths.resolve(path).file
        require(file.isFile) { "文件不存在" }
        val type = DeviceResourceMime.type(file)
        if (type.startsWith("image/") && file.extension.lowercase() != "svg") preview(path)
        else main.invoke {
            check(foreground()) { "请在运行窗口前台打开文件" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.device-files", file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.clipData = android.content.ClipData.newRawUri("", uri)
            try { context.startActivity(intent) }
            catch (_: android.content.ActivityNotFoundException) { error("设备上没有可打开此文件的应用") }
        }
        JSONObject().put("ok", true).put("value", JSONObject.NULL)
    } catch (error: Exception) { ApiErrors.failure(error) }

    override fun close() = main.close()
}
