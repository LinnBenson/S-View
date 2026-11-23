package io.todu.sview.runtime.api

import io.todu.sview.runtime.cleanupResource
import android.content.Context
import io.todu.sview.data.Project
import io.todu.sview.data.WindowDimension
import org.json.JSONObject
import java.io.Closeable

/** 按命名空间路由，系统能力与窗口交互各自管理生命周期。 */
class RuntimeApiBridge(context: Context, project: Project, repository: io.todu.sview.data.ProjectRepository, foreground: () -> Boolean,
    resize: (WindowDimension, WindowDimension, Boolean, (Result<Unit>) -> Unit) -> Unit, dismiss: () -> Unit,
    location: (io.todu.sview.data.WindowLocation, Boolean, (Result<Unit>) -> Unit) -> Unit, removeClose: () -> Unit,
    margin: (io.todu.sview.data.WindowMargins, Boolean, (Result<Unit>) -> Unit) -> Unit,
    cornerRadius: (Double, Boolean, (Result<Unit>) -> Unit) -> Unit,
    exitApplication: () -> Unit,
    nfcEvent: (JSONObject) -> Unit = {},
    localServerEvent: (JSONObject) -> Unit = {}) : Closeable {
    private val customServers = CustomServerApiBridge(context, repository, project.id, localServerEvent)
    private val localServers = LocalServerApiBridge(context, localServerEvent)
    private val hotspot = HotspotApiBridge(context, foreground, localServerEvent)
    private val nfc = NfcApiBridge(context, foreground, nfcEvent)
    private val database = DatabaseApiBridge(context)
    private val textTools = ScriptTextApiBridge()
    private val logs = ScriptLogApiBridge(context, project.id)
    private val system = SystemApiBridge(context, project, foreground)
    private val preview = io.todu.sview.runtime.preview.ImagePreviewController(context, project.id, foreground)
    private val deviceLinks = DeviceLinkApiBridge(context, project.id, foreground, preview::open)
    private val interaction = InteractionApiBridge(resize, dismiss, location, removeClose, margin, cornerRadius, preview::open, exitApplication)

    fun invoke(request: JSONObject): JSONObject = when {
        request.optString("method") in listOf("network.server", "network.server.close") -> customServers.invoke(request)
        request.optString("method") == "resource.openDevice" -> deviceLinks.invoke(request)
        request.optString("method") in listOf("network.chatServer", "network.uploadServer", "network.localServer.send", "network.localServer.close") -> localServers.invoke(request)
        request.optString("method").startsWith("network.hotspot") -> hotspot.invoke(request)
        request.optString("method").startsWith("system.nfc.") -> nfc.invoke(request)
        request.optString("method") in listOf("script.md5", "script.qrcode") -> textTools.invoke(request)
        request.optString("method") == "script.log" -> logs.invoke(request)
        request.optString("method").startsWith("script.db.") -> database.invoke(request)
        request.optString("method").startsWith("interaction.") -> interaction.invoke(request)
        else -> system.invoke(request)
    }

    fun serverRequest(socket: java.net.Socket, input: java.io.BufferedInputStream, target: String, length: Int, done: () -> Unit) =
        customServers.dispatch(socket, input, target, length, done)

    fun responseSent(request: JSONObject, response: JSONObject) = interaction.responseSent(request, response)
    fun pause() = nfc.stop("background")
    fun background() { preview.dismiss(); system.background() }
    override fun close() {
        cleanupResource("自定义服务器") { customServers.close() }
        cleanupResource("局域网服务") { localServers.close() }
        cleanupResource("本地热点") { hotspot.close() }
        nfc.close()
        cleanupResource("设备文件链接") { deviceLinks.close() }
        cleanupResource("图片预览") { preview.close() }
        cleanupResource("交互接口") { interaction.close() }
        cleanupResource("系统接口") { system.close() }
    }
}
