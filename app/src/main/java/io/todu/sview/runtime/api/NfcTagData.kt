package io.todu.sview.runtime.api

import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import org.json.JSONArray
import org.json.JSONObject

/** 使用系统发现标签时读取的 NDEF 快照，不再次建立阻塞式标签连接。 */
internal object NfcTagData {
    fun read(tag: Tag): JSONObject {
        val result = JSONObject().put("id", hex(tag.id))
            .put("technologies", JSONArray(tag.techList.map { it.substringAfterLast('.') }))
            .put("ndef", JSONObject.NULL)
        val ndef = Ndef.get(tag) ?: return result
        val message = ndef.cachedNdefMessage ?: return result
        return result.put("ndef", JSONObject().put("type", ndef.type).put("maxSize", ndef.maxSize)
            .put("writable", ndef.isWritable).put("bytes", bytes(message.toByteArray()))
            .put("records", JSONArray(message.records.map(::record))))
    }

    private fun record(record: NdefRecord): JSONObject {
        val result = JSONObject().put("tnf", record.tnf.toInt()).put("type", bytes(record.type))
            .put("id", bytes(record.id)).put("payload", bytes(record.payload))
        // 无法解释的记录仍保留原始字节，单条内容异常不丢弃整个标签。
        if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
            text(record.payload)?.let { (language, text) -> result.put("language", language).put("text", text) }
        }
        runCatching { record.toUri() }.getOrNull()?.let { result.put("uri", it.toString()) }
        runCatching { record.toMimeType() }.getOrNull()?.let { result.put("mimeType", it) }
        return result
    }

    /** 文本记录首字节包含语言长度及 UTF-16 标记。 */
    fun text(payload: ByteArray): Pair<String, String>? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt() and 255
        val length = status and 63
        if (status and 64 != 0 || payload.size < length + 1) return null
        val language = String(payload, 1, length, Charsets.US_ASCII)
        val charset = if (status and 128 != 0) Charsets.UTF_16 else Charsets.UTF_8
        return language to String(payload, length + 1, payload.size - length - 1, charset)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
    private fun bytes(value: ByteArray) = JSONArray(value.map { it.toInt() and 255 })
}
