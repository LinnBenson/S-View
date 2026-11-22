package io.todu.sview.runtime.overlay

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.os.*
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.todu.sview.MainActivity
import io.todu.sview.R
import io.todu.sview.data.WorkspaceOwner
import io.todu.sview.runtime.RuntimeWindow
import io.todu.sview.ui.theme.SViewTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** 用户从快捷方式启动的可见悬浮运行；关闭最后一个窗口即结束前台服务。 */
class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val saved = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = saved.savedStateRegistry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: OverlayWindow? = null
    private var request: Job? = null
    private val model by lazy { WorkspaceOwner.model(application) }
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, _ ->
        if (!Settings.canDrawOverlays(this)) Handler(Looper.getMainLooper()).post { stopSelf() }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stopSelf() }
    }

    override fun onCreate() {
        super.onCreate()
        saved.performAttach(); saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("floating_runtime", "悬浮运行", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "close") { stopSelf(); return START_NOT_STICKY }
        val id = intent?.getStringExtra("projectId")
        if (id == null) { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) {
            runCatching { startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("widgetRunToken", io.todu.sview.widget.WidgetRunRequest.issue(id))) }
            stopSelf(); return START_NOT_STICKY
        }
        startForeground(1001, notification("正在准备项目"))
        request?.cancel()
        request = scope.launch {
            try {
                withTimeoutOrNull(15000) { snapshotFlow { model.ready }.first { it } } ?: error("工作区加载失败")
                val project = model.projects.find { it.id == id } ?: error("项目不存在，请重新配置快捷方式")
                // 复用工作区的保存和准备队列，避免读取编辑器尚未落盘的代码。
                val ready = CompletableDeferred<io.todu.sview.data.Project>()
                model.prepareRun(id) { ready.complete(it) }
                val prepared = withTimeoutOrNull(15000) { ready.await() } ?: error(model.error ?: "项目准备超时")
                window?.close(); window = null
                if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                // Service 本身没有显示器关联；先创建显示器上下文，再创建该屏幕上的窗口上下文。
                val display = getSystemService(android.hardware.display.DisplayManager::class.java)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: error("当前显示器不可用")
                val displayContext = createDisplayContext(display)
                val context = if (Build.VERSION.SDK_INT >= 30) displayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else displayContext
                val view = ComposeView(context)
                view.setViewTreeLifecycleOwner(this@OverlayService)
                view.setViewTreeSavedStateRegistryOwner(this@OverlayService)
                view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                val host = OverlayWindow(context, context.getSystemService(WindowManager::class.java), view)
                window = host
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                view.setContent {
                    SViewTheme { CompositionLocalProvider(LocalRuntimeOverlay provides host) {
                        RuntimeWindow(prepared, model.repository, { model.appendLog(prepared, it) },
                            model::cornerRadiusRunProject, model::marginRunProject, model::locateRunProject, model::resizeRunProject,
                            { message -> Toast.makeText(this@OverlayService, message, Toast.LENGTH_LONG).show() }, onClose = { stopSelf() })
                    } }
                }
                host.attach(prepared.backdropTransparency) { stopSelf() }
                getSystemService(NotificationManager::class.java).notify(1001, notification(project.name))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.e("OverlayService", "悬浮运行启动失败", error)
                Toast.makeText(this@OverlayService, error.message ?: "无法启动悬浮窗口", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(name: String): Notification {
        val close = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction("close"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "floating_runtime").setSmallIcon(R.drawable.lucide_code)
            .setContentTitle(name).setContentText("项目正在悬浮窗口中运行").setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "关闭", close).build()).build()
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); window?.configurationChanged() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        request?.cancel()
        window?.close(); window = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        runCatching { unregisterReceiver(screenReceiver) }
        getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionListener)
        model.flushLogWrites()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
