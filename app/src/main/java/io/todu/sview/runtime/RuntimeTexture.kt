package io.todu.sview.runtime

import android.graphics.SurfaceTexture
import android.view.TextureView
import org.mozilla.geckoview.GeckoView

/** 保留引擎的表面管理，只在纹理实际更新后通知加载层可以交接。 */
internal fun GeckoView.observeRuntimeTexture(onFrame: () -> Unit) {
    for (index in 0 until childCount) {
        val texture = getChildAt(index) as? TextureView ?: continue
        val delegate = texture.surfaceTextureListener ?: continue
        texture.isOpaque = false
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) =
                delegate.onSurfaceTextureAvailable(surface, width, height)

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) =
                delegate.onSurfaceTextureSizeChanged(surface, width, height)

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean =
                delegate.onSurfaceTextureDestroyed(surface)

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                delegate.onSurfaceTextureUpdated(surface)
                // 首帧后恢复引擎监听，不让每一帧继续经过 Compose 状态回调。
                texture.surfaceTextureListener = delegate
                onFrame()
            }
        }
    }
}
