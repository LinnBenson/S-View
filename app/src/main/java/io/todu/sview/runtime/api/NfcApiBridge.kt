package io.todu.sview.runtime.api

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.Closeable

/** NFC 会话状态只在主线程修改；系统读卡回调只负责转换快照并投递结果。 */
internal class NfcApiBridge(
    private val context: Context,
    private val foreground: () -> Boolean,
    private val publish: (JSONObject) -> Unit
) : Closeable {
    private val main = MainThreadCalls()
    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var disposed = false

    private class Session(val id: String, val activity: Activity, val adapter: NfcAdapter, val interval: Long) {
        val reads = LinkedHashMap<String, Long>()
        var receiver: BroadcastReceiver? = null
    }

    fun invoke(request: JSONObject): JSONObject = try {
        val args = request.getJSONObject("args")
        val id = args.getString("id")
        require(id.length in 1..100) { "无效的监听编号" }
        val value = main.invoke {
            when (request.getString("method")) {
                "system.nfc.start" -> {
                    val number = args.get("repeatInterval") as? Number ?: error("repeatInterval 应为数字")
                    val interval = number.toDouble()
                    require(interval.isFinite() && interval >= 0 && interval <= 60000 && interval % 1 == 0.0) { "repeatInterval 应为 0–60000 的整数" }
                    start(id, interval.toLong())
                }
                "system.nfc.close" -> { if (session?.id == id) stop("manual"); JSONObject.NULL }
                else -> error("未知 NFC 接口")
            }
        }
        JSONObject().put("ok", true).put("value", value)
    } catch (error: Exception) {
        ApiErrors.failure(error)
    }

    private fun start(id: String, interval: Long): Any {
        if (disposed || !foreground()) return failure("NOT_FOREGROUND", "请在运行窗口位于前台时启动 NFC")
        if (session != null || owner != null) return failure("ALREADY_LISTENING", "已有 NFC 监听正在运行")
        val activity = activity() ?: return failure("START_FAILED", "NFC 读取需要在应用内运行")
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return failure("UNSUPPORTED", "设备不支持 NFC")
        if (!adapter.isEnabled) return failure("DISABLED", "请先开启系统 NFC")
        val next = Session(id, activity, adapter, interval)
        session = next
        owner = this
        return try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (session !== next || intent?.action != NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) return
                    if (intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF) != NfcAdapter.STATE_ON) {
                        emit(next, "error", failure("DISABLED", "系统 NFC 已关闭"))
                        stop("error")
                    }
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            next.receiver = receiver
            // 保留系统 NDEF 检测，才能取得 Ndef 技术及发现时的消息快照。
            adapter.enableReaderMode(activity, { tag ->
                val result = runCatching { NfcTagData.read(tag) }
                handler.post { if (session === next) deliver(next, result) }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE, null)
            JSONObject().put("started", true)
        } catch (error: Exception) {
            // 启动失败由请求响应统一回调，避免重复 onError/onClose。
            stop("error", notify = false)
            failure("START_FAILED", error.message ?: "无法启动 NFC")
        }
    }

    private fun deliver(current: Session, result: Result<JSONObject>) {
        if (!foreground()) { stop("background"); return }
        result.fold(onSuccess = { tag ->
            val key = tag.getString("id") + tag.getJSONArray("technologies").toString()
            val now = SystemClock.elapsedRealtime()
            val previous = current.reads[key]
            if (previous != null && now - previous < current.interval) return
            current.reads[key] = now
            if (current.reads.size > 128) current.reads.remove(current.reads.keys.first())
            emit(current, "message", tag)
        }, onFailure = { error ->
            val code = if (error is android.nfc.TagLostException) "TAG_LOST" else "READ_FAILED"
            emit(current, "error", failure(code, error.message ?: "无法读取 NFC 标签"))
        })
    }

    private fun emit(current: Session, type: String, value: Any) {
        publish(JSONObject().put("id", current.id).put("type", type).put("value", value))
    }

    /** 生命周期与脚本关闭共用释放入口，重复调用不再发送关闭通知。 */
    fun stop(reason: String, notify: Boolean = true) {
        val previous = session ?: return
        session = null
        if (owner === this) owner = null
        runCatching { previous.adapter.disableReaderMode(previous.activity) }
        previous.receiver?.let { runCatching { context.unregisterReceiver(it) } }
        if (notify) emit(previous, "close", reason)
    }

    override fun close() {
        disposed = true
        stop("windowClosed")
        main.close()
        handler.removeCallbacksAndMessages(null)
    }

    private fun activity(): Activity? {
        var value = context
        while (value is ContextWrapper) {
            if (value is Activity) return value
            val base = value.baseContext
            if (base === value) break
            value = base
        }
        return null
    }

    private fun failure(code: String, message: String) = JSONObject().put("code", code).put("message", message)

    private companion object {
        // Android reader mode 按 Activity 独占，不能让另一窗口覆盖正在工作的监听。
        var owner: NfcApiBridge? = null
    }
}
