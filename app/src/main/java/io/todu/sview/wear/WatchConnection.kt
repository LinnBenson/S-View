package io.todu.sview.wear

import android.content.Context
import androidx.compose.runtime.*
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 只在连接页监听节点变化；传输使用按需打开的通道，平时不保活。 */
class WatchConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val snapshot: suspend (List<String>, Set<String>, Set<String>) -> File,
) {
    var devices by mutableStateOf<List<Node>>(emptyList()); private set
    var deviceId by mutableStateOf<String?>(null); private set
    var selected by mutableStateOf<List<String>>(emptyList()); private set
    var expanded by mutableStateOf<Set<String>>(emptySet()); private set
    var tileProjects by mutableStateOf<Set<String>>(emptySet()); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var progress by mutableStateOf<Float?>(null); private set
    private var statusReset: Job? = null
    private val preferences = context.getSharedPreferences("watch-connection", Context.MODE_PRIVATE)
    private val capability by lazy { Wearable.getCapabilityClient(context) }
    private val listener = CapabilityClient.OnCapabilityChangedListener { info -> scope.launch { updateNodes(info) } }
    private var listening = false
    private var refreshJob: Job? = null
    private var syncJob: Job? = null
    private var activeChannel: ChannelClient.Channel? = null

    init {
        val array = runCatching { JSONArray(preferences.getString("projects", "[]")) }.getOrDefault(JSONArray())
        selected = List(array.length()) { array.optString(it) }.filter(String::isNotEmpty).distinct()
        expanded = preferences.getStringSet("expanded", emptySet()).orEmpty().intersect(selected.toSet())
        tileProjects = selected.filter { it in preferences.getStringSet("tile-projects", emptySet()).orEmpty() }.take(WatchTileStore.MAX_PROJECTS).toSet()
        deviceId = preferences.getString("device", null)
    }

    fun start() {
        if (listening) return
        listening = true
        capability.addListener(listener, CAPABILITY).addOnFailureListener { message = "无法监听手表连接" }
        refresh()
    }

    fun stop() {
        listening = false
        capability.removeListener(listener)
        refreshJob?.cancel()
    }

    /** 用户退出应用时结束传输，通道关闭会唤醒正在等待数据的另一端。 */
    fun close() {
        stop()
        activeChannel?.let { Wearable.getChannelClient(context).close(it) }
        syncJob?.cancel()
        statusReset?.cancel()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try { updateNodes(capability.getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).suspendResult()) }
            catch (error: Exception) { if (error is CancellationException) throw error; devices = emptyList(); message = "未找到手表，请检查系统配对及手表上的 S View" }
        }
    }

    private fun updateNodes(info: CapabilityInfo) {
        devices = info.nodes.filter { it.isNearby }.sortedBy { it.displayName }
        if (devices.none { it.id == deviceId }) deviceId = devices.firstOrNull()?.id
    }

    fun chooseDevice(id: String) { deviceId = id; preferences.edit().putString("device", id).apply() }
    fun toggle(id: String) {
        if (busy) return
        selected = if (id in selected) selected - id else selected + id
        saveSelection()
    }
    /** 拖动期间只更新内存顺序，松手后统一写入偏好。 */
    fun reorder(id: String, target: String) {
        if (busy) return
        val list = selected.toMutableList()
        val from = list.indexOf(id); val to = list.indexOf(target)
        if (from >= 0 && to >= 0 && from != to) {
            list.add(to, list.removeAt(from)); selected = list
        }
    }
    fun saveOrder() { saveSelection() }
    fun addProjects(ids: List<String>) {
        if (busy) return
        selected = (selected + ids).distinct()
        saveSelection()
    }
    fun reconcile(ids: Set<String>) { if (!busy) { selected = selected.filter { it in ids }; saveSelection() } }
    /** 每个同步项目独立记住扩展状态，移除后重新添加时默认关闭。 */
    fun toggleExpanded(id: String) {
        if (busy || id !in selected) return
        expanded = if (id in expanded) expanded - id else expanded + id
        saveSelection()
    }

    /** 磁贴最多容纳八个入口，关闭后仍保留普通项目列表。 */
    fun toggleTile(id: String) {
        if (busy || id !in selected) return
        if (id !in tileProjects && tileProjects.size >= WatchTileStore.MAX_PROJECTS) {
            android.widget.Toast.makeText(context, "最多显示 8 个项目", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        tileProjects = if (id in tileProjects) tileProjects - id else tileProjects + id
        saveSelection()
    }

    private fun saveSelection() {
        tileProjects = tileProjects.intersect(selected.toSet())
        expanded = expanded.intersect(selected.toSet())
        preferences.edit().putString("projects", JSONArray(selected).toString())
            .putStringSet("expanded", expanded).putStringSet("tile-projects", tileProjects).apply()
    }

    fun sync() {
        if (busy) return
        val node = devices.find { it.id == deviceId } ?: return
        val ids = selected.toList()
        val expandedIds = expanded.toSet()
        val tileIds = tileProjects.toSet()
        statusReset?.cancel()
        progress = null
        busy = true
        syncJob = scope.launch {
            var archive: File? = null
            try {
                message = "准备同步…"
                archive = snapshot(ids, expandedIds, tileIds)
                message = "正在传输 0%"
                progress = 0f
                transfer(node.id, archive)
                message = "已同步 ${ids.size} 个项目"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = "同步未完成：${error.message ?: "连接已断开"}"
            } finally {
                archive?.let { io.todu.sview.storage.TemporaryFiles.get(context).release(it) }
                busy = false
                progress = null
                statusReset = scope.launch { delay(4000); message = "" }
            }
        }
    }

    private suspend fun transfer(node: String, file: File) {
        val client = Wearable.getChannelClient(context)
        val channel = client.openChannel(node, PATH).suspendResult()
        activeChannel = channel
        // 超时主动关闭通道，解除底层流上的阻塞读写。
        val timeout = scope.launch { delay(300000); client.close(channel) }
        try {
            withContext(Dispatchers.IO) {
                val output = DataOutputStream(client.getOutputStream(channel).suspendResult())
                output.writeInt(1); output.writeLong(file.length()); output.writeUTF(digest(file))
                // 只在整数百分比变化时刷新界面，避免每个数据块触发重组。
                val size = file.length()
                var sent = 0L
                var lastPercent = -1
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        sent += count
                        val percent = (sent * 100 / size).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            withContext(Dispatchers.Main) {
                                progress = percent / 100f
                                message = "正在传输 $percent%"
                            }
                        }
                    }
                }
                output.flush()
                withContext(Dispatchers.Main) { progress = null; message = "手表正在更新…" }
                val input = DataInputStream(client.getInputStream(channel).suspendResult())
                val accepted = input.readBoolean(); val result = input.readUTF()
                check(accepted) { result }
            }
        } finally { timeout.cancel(); client.close(channel); activeChannel = null }
    }

    companion object {
        const val CAPABILITY = "sview_watch_v1"
        const val PATH = "/sview/sync/v1"
        fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** 不阻塞主线程等待 Google Play 服务回调。 */
internal suspend fun <T> Task<T>.suspendResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
