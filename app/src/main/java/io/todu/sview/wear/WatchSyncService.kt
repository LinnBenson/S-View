package io.todu.sview.wear

import android.app.Application
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.todu.sview.data.WorkspaceOwner
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** 配对设备主动同步时才由系统拉起，不创建常驻服务或后台轮询。 */
class WatchSyncService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val client = Wearable.getChannelClient(this)
        if (WatchDevice.isWatch(this) && channel.path == WatchLogsProtocol.PATH) {
            sendLogs(channel)
            return
        }
        if (!WatchDevice.isWatch(this) || channel.path != WatchConnection.PATH) { client.close(channel); return }
        // WearableListenerService 在工作线程分发通道事件，返回前完成接收与回执。
        runBlocking(Dispatchers.IO) {
            val timeout = launch { delay(300000); client.close(channel) }
            var archive: File? = null
            var staging: File? = null
            var ownsSync = false
            val model = withContext(Dispatchers.Main) { WorkspaceOwner.model(application as Application) }
            try {
                withContext(Dispatchers.Main) {
                    withTimeout(30000) { model.awaitReady() }
                    model.beginWatchSync(); ownsSync = true
                }
                archive = model.repository.temporaryFiles.create(cacheDir, "watch-receive-", ".zip")
                val input = DataInputStream(client.getInputStream(channel).suspendResult())
                require(input.readInt() == 1) { "同步版本不匹配" }
                val size = input.readLong(); require(size in 1..WatchSyncArchive.MAX_BYTES) { "同步包大小无效" }
                val hash = input.readUTF(); require(hash.matches(Regex("[0-9a-f]{64}")))
                archive.outputStream().use { output ->
                    var remaining = size; val buffer = ByteArray(65536)
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(count > 0) { "同步传输中断" }
                        output.write(buffer, 0, count); remaining -= count
                    }
                }
                check(WatchConnection.digest(archive) == hash) { "同步文件校验失败" }
                staging = WatchWorkspaceStore.newStaging(this@WatchSyncService)
                val snapshot = WatchSyncArchive.read(archive, staging)
                WatchDatabaseSnapshot.write(File(staging, "shared-values.db"), snapshot.database)
                withContext(Dispatchers.Main + NonCancellable) { model.applyWatchSnapshot(staging, snapshot.projects) }
                staging = null
                DataOutputStream(client.getOutputStream(channel).suspendResult()).apply { writeBoolean(true); writeUTF("已同步"); flush() }
            } catch (error: Exception) {
                runCatching {
                    DataOutputStream(client.getOutputStream(channel).suspendResult()).apply {
                        writeBoolean(false); writeUTF((error.message ?: "同步失败").take(500)); flush()
                    }
                }
            } finally {
                timeout.cancel()
                withContext(Dispatchers.Main + NonCancellable) { if (ownsSync) model.endWatchSync() }
                archive?.let { model.repository.temporaryFiles.release(it) }
                staging?.let { if (it != WatchWorkspaceStore.root(this@WatchSyncService)) it.deleteRecursively() }
                client.close(channel)
            }
        }
    }
    /** 与资源同步共用受配对保护的通道，读取前等待工作区初始化。 */
    private fun sendLogs(channel: ChannelClient.Channel) = runBlocking(Dispatchers.IO) {
        val client = Wearable.getChannelClient(this@WatchSyncService)
        val timeout = launch { delay(30000); client.close(channel) }
        try {
            val id = DataInputStream(client.getInputStream(channel).suspendResult()).readUTF()
            val result = withContext(Dispatchers.Main) {
                val model = WorkspaceOwner.model(application as Application)
                withTimeout(15000) { model.awaitReady() }
                model.readWatchLogs(id)
            }
            WatchLogsProtocol.write(DataOutputStream(client.getOutputStream(channel).suspendResult()), result)
        } catch (error: Exception) {
            runCatching {
                WatchLogsProtocol.write(DataOutputStream(client.getOutputStream(channel).suspendResult()),
                    org.json.JSONObject().put("error", error.message ?: "无法读取日志"))
            }
        } finally { timeout.cancel(); client.close(channel) }
    }

}
