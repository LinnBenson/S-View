package io.todu.sview.runtime.preview

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.todu.sview.data.ProjectRepository
import io.todu.sview.runtime.api.MainThreadCalls
import io.todu.sview.runtime.storage.StoragePaths
import io.todu.sview.storage.DeviceStorage
import io.todu.sview.wear.WatchDevice
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 预览归属运行会话；解码在调用线程完成，窗口创建与销毁限定在主线程。 */
internal class ImagePreviewController(private val context: Context, projectId: String, private val foreground: () -> Boolean) : Closeable {
    private val paths by lazy { StoragePaths(ProjectRepository(context).root(projectId)) { DeviceStorage.root(context) } }
    private val main = MainThreadCalls()
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val generation = AtomicInteger()
    private var dialog: Dialog? = null

    /** Promise 在成功显示时完成；重复调用替换当前预览，不叠加窗口。 */
    fun open(path: String) {
        check(busy.compareAndSet(false, true)) { "正在加载图片，请稍后重试" }
        val version = generation.get()
        fun ensureActive() {
            check(!closed.get() && version == generation.get()) { "图片预览已取消" }
            if (!foreground()) throw io.todu.sview.runtime.api.ApiException("NOT_FOREGROUND", "请在运行窗口位于前台时调用此接口")
        }
        try {
            main.invoke { ensureActive(); JSONObject.NULL }
            val watch = WatchDevice.isWatch(context)
            val display = context.resources.displayMetrics
            val bitmap = PreviewImageLoader.load(paths.resolve(path).file,
                if (watch) maxOf(display.widthPixels, display.heightPixels) else null)
            // 解码期间可能已关闭窗口；尚未交给视图的位图可立即回收。
            try { ensureActive() } catch (error: Exception) { bitmap.recycle(); throw error }
            main.invoke {
                ensureActive()
                val next = Dialog(context, android.R.style.Theme_Material_NoActionBar_Fullscreen)
                val window = requireNotNull(next.window)
                if (!hasActivity(context)) window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                // 预览是独立窗口，同样保持亮屏；关闭窗口后由系统撤销此标记。
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.setBackgroundDrawableResource(android.R.color.black)
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
                root.addView(PreviewImageView(context, bitmap, watch) { next.dismiss() }, FrameLayout.LayoutParams(-1, -1))
                next.setContentView(root)
                next.setOnDismissListener {
                    root.removeAllViews()
                    if (dialog === next) dialog = null
                }
                try {
                    next.show()
                    window.setLayout(-1, -1)
                    WindowCompat.getInsetsController(window, root).apply {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                    dialog?.dismiss()
                    dialog = next
                } catch (error: Exception) { next.dismiss(); throw error }
                JSONObject.NULL
            }
        } finally { busy.set(false) }
    }

    /** 后台或退出时取消迟到的解码结果，并关闭当前窗口。 */
    fun dismiss() {
        generation.incrementAndGet()
        val action = { dialog?.dismiss(); dialog = null }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    override fun close() { closed.set(true); dismiss(); main.close() }

    /** 悬浮脚本没有 Activity token，使用已有悬浮窗授权创建预览窗口。 */
    private fun hasActivity(value: Context): Boolean {
        var current = value
        while (current is ContextWrapper) {
            if (current is Activity) return true
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return current is Activity
    }
}
