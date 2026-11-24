package io.todu.sview.lan

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 用户启动后通过共用前台服务维持监听，停止时释放会话。 */
class LanEditorController(private val context: Context, private val dispatch: suspend (String, JSONObject) -> LanResponse) : LocalEditorController {
    override var running by mutableStateOf(false); private set
    override var addresses by mutableStateOf<List<String>>(emptyList()); private set
    override var message by mutableStateOf<String?>(null); private set
    override var busy by mutableStateOf(false); private set
    override val statusText: String get() = if (busy) "启动中" else if (running) "运行中" else "未启动"
    private var server: LanHttpServer? = null
    private var generation = 0
    private var keepAlive: io.todu.sview.lan.LocalEditorService.Session? = null
    override val savedPort: String get() = context.getSharedPreferences("lan-editor", Context.MODE_PRIVATE).getInt("port", 8899).toString()

    override suspend fun savedPassword(): String = try {
        withContext(Dispatchers.IO) { LanPasswordStore(context).read() }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        message = "无法读取已保存密码，请重新输入"
        ""
    }

    override suspend fun start(portText: String, password: String) {
        if (running || busy) return
        busy = true
        val current = ++generation
        var candidate: LanHttpServer? = null
        try {
            val port = portText.toIntOrNull()
            require(port != null && port in 1024..65535) { "端口应为 1024 到 65535" }
            require(password.length in 4..128) { "访问密码应为 4 到 128 个字符" }
            keepAlive = io.todu.sview.lan.LocalEditorService.acquire(context, "HTTP", ::stop)
            if (current != generation) return
            val hosts = withContext(Dispatchers.IO) { LocalEditorAddresses.hosts() }
            require(hosts.isNotEmpty()) { "请先连接 Wi-Fi 或开启热点" }
            val created = withContext(Dispatchers.IO) {
                LanHttpServer(context, hosts, port, password) { action, body ->
                    runBlocking { dispatch(action, body) }
                }.also { candidate = it }
            }
            if (current != generation) { created.close(); return }
            withContext(Dispatchers.IO) {
                LanPasswordStore(context).save(password)
                check(context.getSharedPreferences("lan-editor", Context.MODE_PRIVATE).edit().putInt("port", port).commit()) { "无法保存端口" }
            }
            if (current != generation) return
            server = created; running = true; addresses = hosts.map { "http://${it.hostAddress}:$port" }; message = null
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            message = if (error is java.net.BindException) "端口已被占用，请更换端口" else error.message ?: "无法启动服务"
        } finally {
            if (server !== candidate) candidate?.close()
            if (!running) { keepAlive?.close(); keepAlive = null }
            busy = false
        }
    }

    override fun stop() {
        ++generation
        keepAlive?.close(); keepAlive = null
        server?.close(); server = null; running = false; addresses = emptyList()
    }
}
