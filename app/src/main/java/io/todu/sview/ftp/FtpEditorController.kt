package io.todu.sview.ftp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.todu.sview.lan.LocalEditorAddresses
import io.todu.sview.lan.LanPasswordStore
import io.todu.sview.lan.LocalEditorController
import io.todu.sview.wear.WatchDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 仅手机按需创建 FTP 服务；后台继续监听，主动停止时关闭控制和数据连接。 */
class FtpEditorController(private val context: Context, private val access: (FtpRequest) -> FtpResult) : LocalEditorController {
    override var running by mutableStateOf(false); private set
    override var busy by mutableStateOf(false); private set
    override var addresses by mutableStateOf<List<String>>(emptyList()); private set
    override var message by mutableStateOf<String?>(null); private set
    override val statusText get() = if (busy) "启动中" else if (running) "运行中" else "未启动"
    private val preferences get() = context.getSharedPreferences("ftp-editor", Context.MODE_PRIVATE)
    override val savedPort get() = preferences.getInt("port", 2121).toString()
    private var server: FtpServer? = null
    private var generation = 0
    private var keepAlive: io.todu.sview.lan.LocalEditorService.Session? = null

    override suspend fun savedPassword(): String = try {
        withContext(Dispatchers.IO) { LanPasswordStore(context, "ftp-password").read() }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        message = "无法读取已保存密码，请重新输入"
        ""
    }

    override suspend fun start(portText: String, password: String) {
        if (running || busy) return
        busy = true
        val current = ++generation
        var candidate: FtpServer? = null
        try {
            check(!WatchDevice.isWatch(context)) { "请在手机上启动 FTP 编辑器" }
            val port = portText.toIntOrNull()
            require(port != null && port in 1024..65535) { "端口应为 1024 到 65535" }
            require(password.length in 4..128 && password.none { it < ' ' }) { "访问密码应为 4 到 128 个可见字符" }
            keepAlive = io.todu.sview.lan.LocalEditorService.acquire(context, "FTP", ::stop)
            if (current != generation) return
            val hosts = withContext(Dispatchers.IO) { LocalEditorAddresses.hosts() }
            require(hosts.isNotEmpty()) { "请先连接 Wi-Fi 或开启热点" }
            withContext(Dispatchers.IO) {
                LanPasswordStore(context, "ftp-password").save(password)
                check(preferences.edit().putInt("port", port).commit()) { "无法保存端口" }
                candidate = FtpServer(hosts, port, password) { request -> access(request.copy(session = current)) }
            }
            if (generation != current) return
            server = candidate
            addresses = hosts.map { "ftp://${it.hostAddress}:$port" }
            running = true
            message = null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            message = if (error is java.net.BindException) "端口已被占用，请更换端口" else error.message ?: "无法启动服务"
        } finally {
            if (server !== candidate) candidate?.close()
            if (!running) { keepAlive?.close(); keepAlive = null }
            busy = false
        }
    }

    /** 重启后拒绝旧连接仍在队列中等待的操作。 */
    fun accepts(request: FtpRequest): Boolean = running && request.session == generation

    override fun stop() {
        ++generation
        keepAlive?.close(); keepAlive = null
        server?.close()
        server = null
        running = false
        addresses = emptyList()
    }
}
