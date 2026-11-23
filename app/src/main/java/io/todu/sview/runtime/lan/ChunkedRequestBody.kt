package io.todu.sview.runtime.lan

import java.io.InputStream

/** 解码分块请求体，大小上限由接收方按解码后的字节数计算。 */
internal class ChunkedRequestBody(private val source: InputStream) : InputStream() {
    private var remaining = 0L
    private var ending = false
    private var separator = false

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (ending) return -1
        if (remaining == 0L) {
            if (separator) require(source.read() == 13 && source.read() == 10) { "数据块分隔符无效" }
            val size = line().substringBefore(';').trim()
            remaining = size.toLongOrNull(16)?.takeIf { it >= 0 } ?: error("数据块长度无效")
            if (remaining == 0L) {
                var trailerSize = 0
                while (true) {
                    val trailer = line()
                    trailerSize += trailer.length + 2
                    require(trailerSize <= 16384) { "尾部字段过大" }
                    if (trailer.isEmpty()) break
                }
                ending = true
                return -1
            }
            separator = true
        }
        val count = source.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
        check(count > 0) { "请求内容不完整" }
        remaining -= count
        return count
    }

    private fun line(): String {
        val text = StringBuilder()
        while (text.length <= 8192) {
            val byte = source.read()
            check(byte >= 0) { "请求内容不完整" }
            if (byte == 13) { require(source.read() == 10); return text.toString() }
            require(byte != 10) { "数据块格式无效" }
            text.append(byte.toChar())
        }
        error("数据块头部过大")
    }
}
