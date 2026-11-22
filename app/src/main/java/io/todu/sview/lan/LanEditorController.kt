package io.todu.sview.lan

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** 服务状态随工作区存活；不自动启动，也不在后台维持监听或唤醒锁。 */
class LanEditorController(private val context: Context, private val dispatch: suspend (String, JSONObject) -> LanResponse) {
    var running by mutableStateOf(false); private set
    var addresses by mutableStateOf<List<String>>(emptyList()); private set
    var message by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    val statusText: String get() = if (busy) "启动中" else if (running) "运行中" else "未启动"
    private var server: LanHttpServer? = null
    private var generation = 0
    val savedPort: String get() = context.getSharedPreferences("lan-editor", Context.MODE_PRIVATE).getInt("port", 8899).toString()

    suspend fun savedPassword(): String = try {
        withContext(Dispatchers.IO) { LanPasswordStore(context).read() }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        message = "无法读取已保存密码，请重新输入"
        ""
    }

    suspend fun start(portText: String, password: String) {
        if (running || busy) return
        busy = true
        val current = ++generation
        var candidate: LanHttpServer? = null
        try {
            val port = portText.toIntOrNull()
            require(port != null && port in 1024..65535) { "端口应为 1024 到 65535" }
            require(password.length in 4..128) { "访问密码应为 4 到 128 个字符" }
            val urls = withContext(Dispatchers.IO) {
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }.map { "http://${it.hostAddress}:$port" }.distinct()
            }
            require(urls.isNotEmpty()) { "请先连接 Wi-Fi 或开启热点" }
            val created = withContext(Dispatchers.IO) {
                LanHttpServer(context, port, password) { action, body ->
                    runBlocking { dispatch(action, body) }
                }.also { candidate = it }
            }
            if (current != generation) { created.close(); return }
            server = created; running = true; addresses = urls; message = null
            withContext(Dispatchers.IO) {
                LanPasswordStore(context).save(password)
                check(context.getSharedPreferences("lan-editor", Context.MODE_PRIVATE).edit().putInt("port", port).commit()) { "无法保存端口" }
            }
        } catch (error: Exception) {
            message = if (error is java.net.BindException) "端口已被占用，请更换端口" else error.message ?: "无法启动服务"
        } finally {
            if (server !== candidate) candidate?.close()
            busy = false
        }
    }

    fun stop() {
        ++generation
        server?.close(); server = null; running = false; addresses = emptyList()
    }
}
