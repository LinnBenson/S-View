package io.todu.sview.share

import io.todu.sview.storage.TemporaryFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 一次外部启动的私有快照，不写入项目资源或导出配置。 */
class SharedPayload(val text: String, val title: String, val files: List<SharedFile>,
    private val temporary: File? = null, private val store: TemporaryFiles? = null) : Closeable {
    data class SharedFile(val file: File, val name: String, val type: String, val lastModified: Long)
    private val closed = AtomicBoolean(false)
    fun metadata(): JSONObject {
        check(!closed.get()) { "分享会话已结束" }
        return JSONObject().put("text", text).put("title", title).put("files", JSONArray().apply {
            files.forEach { item -> put(JSONObject().put("name", item.name).put("type", item.type)
                .put("size", item.file.length()).put("lastModified", item.lastModified)) }
        })
    }
    fun file(index: Int): File {
        check(!closed.get()) { "分享会话已结束" }
        return files.getOrNull(index)?.file ?: error("分享文件不存在")
    }
    override fun close() {
        if (closed.compareAndSet(false, true) && temporary != null) store?.release(temporary)
    }
    companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
        fun empty(): JSONObject = JSONObject().put("text", "").put("title", "").put("files", JSONArray())
    }
}
