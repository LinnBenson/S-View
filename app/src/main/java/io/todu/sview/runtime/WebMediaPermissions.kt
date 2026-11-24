package io.todu.sview.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.mozilla.geckoview.GeckoSession
import java.io.Closeable
import java.net.URI

/** 网页媒体只使用已由用户授予的设备权限，不保留授权回调或启动后台任务。 */
internal class WebMediaPermissions(
    context: Context,
    private val origin: () -> String?,
    private val foreground: () -> Boolean
) : GeckoSession.PermissionDelegate, Closeable {
    private val context = context.applicationContext
    private var closed = false

    /** 权限统一从手机或手表设置页申请；网页请求不顺带放开定位等其他权限。 */
    override fun onAndroidPermissionsRequest(
        session: GeckoSession, permissions: Array<out String>?, callback: GeckoSession.PermissionDelegate.Callback
    ) {
        if (!closed && foreground() && !permissions.isNullOrEmpty() && permissions.all {
                it in setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) && granted(it)
            }) callback.grant() else callback.reject()
    }

    /** 沿用 Gecko 按 JS constraints 筛选的设备顺序，仅接受摄像头和麦克风。 */
    override fun onMediaPermissionRequest(
        session: GeckoSession, uri: String,
        video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback
    ) {
        if (closed || !foreground() || !sameOrigin(uri) || (video == null && audio == null)) {
            callback.reject(); return
        }
        val camera = video?.firstOrNull {
            it.type == GeckoSession.PermissionDelegate.MediaSource.TYPE_VIDEO &&
                it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA
        }
        val microphone = audio?.firstOrNull {
            it.type == GeckoSession.PermissionDelegate.MediaSource.TYPE_AUDIO &&
                it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE
        }
        if ((video != null && (camera == null || !granted(Manifest.permission.CAMERA))) ||
            (audio != null && (microphone == null || !granted(Manifest.permission.RECORD_AUDIO)))) {
            callback.reject(); return
        }
        callback.grant(camera, microphone)
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** 不允许跳转后的外部页面或其他端口借用项目的设备授权。 */
    private fun sameOrigin(value: String): Boolean = runCatching {
        val requested = URI(value)
        val local = URI(origin() ?: return false)
        requested.scheme == "http" && requested.host == "127.0.0.1" && requested.rawUserInfo == null &&
            local.scheme == requested.scheme && local.host == requested.host && local.port == requested.port
    }.getOrDefault(false)

    /** 先拒绝迟到请求；实际采集轨道由随后关闭的 GeckoSession 统一销毁。 */
    override fun close() { closed = true }
}
