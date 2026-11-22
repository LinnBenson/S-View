package io.todu.sview.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

/** 日志仅在手机打开页面或刷新时读取，不建立后台订阅，也不在手机落盘。 */
object WatchLogsProtocol {
    const val PATH = "/sview/sync/logs/v1"
    private const val MAX_BYTES = 2 * 1024 * 1024

    /** 长度前缀支持超过 writeUTF 限制的日志，并约束接收端内存占用。 */
    fun write(output: DataOutputStream, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "日志内容过大" }
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun read(input: DataInputStream): JSONObject {
        val size = input.readInt()
        require(size in 1..MAX_BYTES) { "日志响应大小无效" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    /** 离开页面或请求超时即关闭通道，解除底层流阻塞。 */
    suspend fun fetch(context: Context, node: String, projectId: String?): JSONObject = coroutineScope {
        val client = Wearable.getChannelClient(context)
        val channel = client.openChannel(node, PATH).suspendResult()
        val closer = launch {
            try { delay(30000) } finally { client.close(channel) }
        }
        try {
            withContext(Dispatchers.IO) {
                val output = DataOutputStream(client.getOutputStream(channel).suspendResult())
                output.writeUTF(projectId.orEmpty())
                output.flush()
                val result = read(DataInputStream(client.getInputStream(channel).suspendResult()))
                check(!result.has("error")) { result.optString("error") }
                result
            }
        } finally { closer.cancel(); client.close(channel) }
    }
}
