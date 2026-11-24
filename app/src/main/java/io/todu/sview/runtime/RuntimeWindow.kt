package io.todu.sview.runtime

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import io.todu.sview.ui.components.AppIcon
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.todu.sview.runtime.api.RuntimeApiBridge
import io.todu.sview.data.Project
import io.todu.sview.data.ProjectRepository
import io.todu.sview.data.WindowDimension
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** 弹窗尺寸由当前可用区域裁剪，保留用户原始尺寸以支持折叠屏恢复。 */
@Composable
fun RuntimeWindow(project: Project, repository: ProjectRepository, onLog: (String) -> Unit,
    persistCornerRadius: (String, Double, (Result<Unit>) -> Unit) -> Unit,
    persistMargin: (String, io.todu.sview.data.WindowMargins, (Result<Unit>) -> Unit) -> Unit,
    persistLocation: (String, io.todu.sview.data.WindowLocation, (Result<Unit>) -> Unit) -> Unit,
    persistSize: (String, WindowDimension, WindowDimension, (Result<Unit>) -> Unit) -> Unit, onError: (String) -> Unit, onReleased: () -> Unit = {}, share: io.todu.sview.share.SharedPayload? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val watch = remember(context) { io.todu.sview.wear.WatchDevice.isWatch(context) }
    val windowBackground = if (watch) Color.Black else Color.White
    val nativeBackground = if (watch) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    val overlay = io.todu.sview.runtime.overlay.LocalRuntimeOverlay.current
    val owner = LocalLifecycleOwner.current
    val density = LocalDensity.current.density.toDouble()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var startupJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val releasedCallback by rememberUpdatedState(onReleased)
    val exitToken = remember { Any() }
    val appearance = remember(project.id) { Animatable(0f) }
    var closing by remember(project.id) { mutableStateOf(false) }
    var aborted by remember(project.id) { mutableStateOf(false) }
    var pageLoaded by remember(project.id) { mutableStateOf(false) }
    var pageSucceeded by remember(project.id) { mutableStateOf(false) }
    var firstComposite by remember(project.id) { mutableStateOf(false) }
    var textureReady by remember(project.id) { mutableStateOf(false) }
    val loading = !pageLoaded || !firstComposite || !textureReady
    var foreground by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var runtimeMargins by remember(project.id) { mutableStateOf(project.margins) }
    var runtimeLocation by remember(project.id) { mutableStateOf(project.location) }
    var runtimeCornerRadius by remember(project.id) { mutableStateOf(project.cornerRadius) }
    var closeVisible by remember(project.id) { mutableStateOf(true) }
    var runtimeWidth by remember(project.id) { mutableStateOf(project.width) }
    var runtimeHeight by remember(project.id) { mutableStateOf(project.height) }
    var server by remember(project.id) { mutableStateOf<ProjectServer?>(null) }
    var session by remember(project.id) { mutableStateOf<GeckoSession?>(null) }
    val mediaPermissions = remember(project.id) {
        WebMediaPermissions(context, { server?.uri }, {
            !closing && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        })
    }
    val closeWindow: () -> Unit = {
        if (!closing) {
            closing = true
            val acknowledged = runCatching { server?.events?.beginClose() }.getOrNull()
            scope.launch {
                coroutineScope {
                    val animation = launch { appearance.animateTo(0f, tween(160, easing = FastOutSlowInEasing)) }
                    withTimeoutOrNull(300) { acknowledged?.await() }
                    animation.join()
                }
                onClose()
            }
        }
    }
    val systemApi = remember(project.id) {
        RuntimeApiBridge(context, project, repository,
            nfcEvent = { server?.events?.nfcEvent(it) },
            notifyMonitorEvent = { server?.events?.notifyMonitorEvent(it) },
            localServerEvent = { server?.events?.localServerEvent(it) },
            exitApplication = { RuntimeAppExit.request(context.applicationContext) },
            foreground = { !closing && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
            resize = { width, height, persist, complete ->
                val apply: (Result<Unit>) -> Unit = { result ->
                    if (result.isSuccess && !closing) { runtimeWidth = width; runtimeHeight = height }
                    complete(result)
                }
                if (persist) persistSize(project.id, width, height, apply) else apply(Result.success(Unit))
            }, dismiss = closeWindow, location = { position, persist, complete ->
                val apply: (Result<Unit>) -> Unit = { result ->
                    if (result.isSuccess && !closing && runtimeLocation != position) {
                        runtimeLocation = position
                        server?.events?.locationChanged(position.value)
                    }
                    complete(result)
                }
                if (persist) persistLocation(project.id, position, apply) else apply(Result.success(Unit))
            }, removeClose = { closeVisible = false }, margin = { margins, persist, complete ->
                val apply: (Result<Unit>) -> Unit = { result ->
                    if (result.isSuccess && !closing && runtimeMargins != margins) {
                        runtimeMargins = margins
                        server?.events?.marginChanged(margins)
                    }
                    complete(result)
                }
                if (persist) persistMargin(project.id, margins, apply) else apply(Result.success(Unit))
            }, cornerRadius = { radius, persist, complete ->
                val apply: (Result<Unit>) -> Unit = { result ->
                    if (result.isSuccess && !closing && runtimeCornerRadius != radius) {
                        runtimeCornerRadius = radius
                        server?.events?.cornerRadiusChanged(radius)
                    }
                    complete(result)
                }
                if (persist) persistCornerRadius(project.id, radius, apply) else apply(Result.success(Unit))
            })
    }
    // 异常退出和组合销毁共用同一释放入口；先断开引用，重复调用不会重复关闭。
    fun releaseResources() {
        val oldSession = session
        val oldServer = server
        session = null
        server = null
        cleanupResource("网页媒体授权") { mediaPermissions.close() }
        cleanupResource("系统接口") { systemApi.close() }
        oldSession?.let {
            cleanupResource("网页媒体回调") { it.permissionDelegate = null }
            cleanupResource("页面进度监听") { it.progressDelegate = null }
            cleanupResource("页面内容监听") { it.contentDelegate = null }
            cleanupResource("页面停用") { it.setActive(false) }
            cleanupResource("页面会话") { BrowserEngine.closeSession(it) }
        }
        oldServer?.let { cleanupResource("资源服务") { it.close() } }
    }

    DisposableEffect(exitToken) {
        RuntimeAppExit.register(exitToken) {
            // 应用退出不能等待后台页面的动画帧或重组，直接完成关闭通知和资源释放。
            if (!closing) {
                closing = true
                val acknowledged = runCatching { server?.events?.beginClose() }.getOrNull()
                withTimeoutOrNull(300) { acknowledged?.await() }
            }
            releaseResources()
            startupJob?.let { it.cancel(); it.join() }
            onClose()
        }
        onDispose { RuntimeAppExit.unregister(exitToken) }
    }

    fun abortWindow(message: String) {
        if (aborted) return
        aborted = true
        closing = true
        releaseResources()
        // 页面进程已不可用，不再等待脚本的关闭确认。
        try { onError(message) } finally { onClose() }
    }

    LaunchedEffect(project.id) {
        startupJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        // 首帧反馈后即可初始化引擎，不等待整个入场动画完成。
        launch { appearance.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
        if (!closing) {
            var created: ProjectServer? = null
            try {
                withContext(Dispatchers.IO) {
                    BrowserEngine.prepareStorage(context)
                    created = RuntimeOrigin.open(context) { port ->
                        ProjectServer(repository, project, onLog, systemApi::invoke, systemApi::responseSent, port, share, systemApi::serverRequest)
                    }
                }
                server = created
                if (!BrowserEngine.initialized) androidx.compose.runtime.withFrameNanos { }
                if (closing) { created?.close(); return@LaunchedEffect }
                val opened = BrowserEngine.openSession(context, project.id, overlay = overlay != null)
                session = opened
                opened.apply {
                    permissionDelegate = mediaPermissions
                    progressDelegate = object : GeckoSession.ProgressDelegate {
                        override fun onPageStart(session: GeckoSession, url: String) { pageLoaded = false; pageSucceeded = false; server?.events?.pageStarted() }
                        override fun onPageStop(session: GeckoSession, success: Boolean) {
                            pageSucceeded = success
                            pageLoaded = true
                            if (!success) server?.events?.pageError("页面加载失败")
                        }
                    }
                    contentDelegate = object : GeckoSession.ContentDelegate {
                        override fun onFirstComposite(session: GeckoSession) { firstComposite = true }
                        override fun onCrash(session: GeckoSession) { abortWindow("运行页面异常退出") }
                        override fun onKill(session: GeckoSession) { abortWindow("运行页面已被系统终止") }
                    }
                    setActive(foreground)
                }
            } catch (error: Exception) {
                cleanupResource("启动中的资源服务") { created?.close() }
                if (error is CancellationException) throw error
                abortWindow(error.message ?: "无法打开运行窗口")
            }
        }
    }
    LaunchedEffect(pageLoaded, pageSucceeded, firstComposite, closing) {
        if (pageLoaded && pageSucceeded && firstComposite && !closing) server?.events?.pageReady()
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) systemApi.pause()
            if (event == Lifecycle.Event.ON_STOP) { systemApi.background(); foreground = false; session?.setActive(false); session?.setFocused(false) }
            if (event == Lifecycle.Event.ON_START) { foreground = true; session?.setActive(true); session?.setFocused(true) }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            closing = true
            cleanupResource("生命周期监听") { owner.lifecycle.removeObserver(observer) }
            releaseResources()
            // 取消启动中的 IO 后，等待其 finally 关闭服务，才能让下一窗口占用同一端口。
            val startup = startupJob
            if (startup == null) releasedCallback() else {
                startup.cancel()
                startup.invokeOnCompletion { releasedCallback() }
            }
        }
    }
    val transparentBackdrop = project.backdropTransparency == 100.0
    val closeFromClick: () -> Unit = {
        if (!closing) {
            if (watch) io.todu.sview.device.DeviceVibration.click(context)
            else haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            closeWindow()
        }
    }
    RuntimeWindowHost(project.backdropTransparency, closeWindow, closeFromClick) {
        if (overlay != null) SideEffect {
            runCatching { overlay.layout(runtimeWidth, runtimeHeight, runtimeMargins, runtimeLocation, project.backdropTransparency, closeFromClick) }
                .onFailure { onError(it.message ?: "无法显示悬浮窗口"); onClose() }
        }
        BoxWithConstraints(Modifier.fillMaxSize().then(if (overlay == null) Modifier.safeDrawingPadding() else Modifier)) {
            val margins = (if (overlay == null) runtimeMargins else io.todu.sview.data.WindowMargins()).resolve(maxWidth.value.toDouble(), maxHeight.value.toDouble(), density)
            BoxWithConstraints(Modifier.fillMaxSize().absolutePadding(
                left = margins.left.toFloat().dp, top = margins.top.toFloat().dp,
                right = margins.right.toFloat().dp, bottom = margins.bottom.toFloat().dp), contentAlignment = when (runtimeLocation) {
            io.todu.sview.data.WindowLocation.CENTER -> Alignment.Center
            io.todu.sview.data.WindowLocation.TOP -> Alignment.TopCenter
            io.todu.sview.data.WindowLocation.BOTTOM -> Alignment.BottomCenter
            io.todu.sview.data.WindowLocation.TOP_LEFT -> AbsoluteAlignment.TopLeft
            io.todu.sview.data.WindowLocation.TOP_RIGHT -> AbsoluteAlignment.TopRight
            io.todu.sview.data.WindowLocation.BOTTOM_LEFT -> AbsoluteAlignment.BottomLeft
            io.todu.sview.data.WindowLocation.BOTTOM_RIGHT -> AbsoluteAlignment.BottomRight
        }) {
            val width = if (overlay != null) maxWidth else runtimeWidth.resolveDp(maxWidth.value.toDouble(), density).dp
            val height = if (overlay != null) maxHeight else runtimeHeight.resolveDp(maxHeight.value.toDouble(), density).dp
            val radius = minOf(runtimeCornerRadius, width.value.toDouble() / 2, height.value.toDouble() / 2).toFloat().dp
            Box(Modifier.size(width, height).graphicsLayer {
                alpha = appearance.value
                scaleX = .94f + .06f * appearance.value
                scaleY = scaleX
            }.clip(RoundedCornerShape(radius)).background(windowBackground)
                .then(if (transparentBackdrop) Modifier.border((1.0 / density).dp, Color(0xFF9E9E9E), RoundedCornerShape(radius)) else Modifier)
                // 页面未加载时也占住窗口范围，避免点击落到遮罩或下层页面。
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})) {
                session?.let { activeSession -> AndroidView(factory = { GeckoView(it).apply {
                    // SurfaceView 独立合成会越过父层圆角，TextureView 才能与窗口一起裁切。
                    setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
                    // 合成回调早于纹理显示，等实际更新后再淡出加载层。
                    observeRuntimeTexture { if (!textureReady) textureReady = true }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(nativeBackground)
                        cornerRadius = radius.value * resources.displayMetrics.density
                    }
                    clipToOutline = true
                    // 首帧遮盖与窗口底色一致，手表加载时不闪白。
                    coverUntilFirstPaint(nativeBackground)
                    setSession(activeSession)
                    server?.let { activeSession.loadUri(it.uri) }
                } }, update = { view ->
                    (view.background as? android.graphics.drawable.GradientDrawable)?.cornerRadius = radius.value * view.resources.displayMetrics.density
                }, modifier = Modifier.fillMaxSize()) }
                // 页面、合成和纹理更新都完成后再淡出；后台停止转动。
                AnimatedVisibility(visible = loading, enter = EnterTransition.None, exit = fadeOut(tween(180))) {
                    Box(Modifier.fillMaxSize().background(windowBackground), contentAlignment = Alignment.Center) {
                        if (foreground) CircularProgressIndicator(
                            modifier = Modifier.size(if (watch) 22.dp else 28.dp), color = if (watch) Color(0xFFB3C8FF) else Color(0xFF8B9AB8),
                            strokeWidth = 2.dp, trackColor = if (watch) Color(0xFF252A34) else Color(0xFFF0F2F6))
                    }
                }
                if (closeVisible && !watch) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(44.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        role = Role.Button, onClickLabel = "关闭运行窗口", enabled = !closing,
                        onClick = closeFromClick), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(26.dp).background(Color.Black.copy(alpha = .24f), CircleShape), contentAlignment = Alignment.Center) {
                        AppIcon("x", "关闭运行窗口", Modifier.size(15.dp), tint = Color.White.copy(alpha = .9f))
                    }
                }
            }
        }
        }
    }
}
