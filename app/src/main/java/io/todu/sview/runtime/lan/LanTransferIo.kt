package io.todu.sview.runtime.lan

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 只标记网络流异常，避免把磁盘读写失败当成客户端取消。 */
internal class LanTransferInterruptedException(cause: Throwable? = null) : IOException("传输已中断", cause)

internal object LanTransferIo {
    /** 保留 EOF 给调用方判断正常结束或正文截断。 */
    fun read(input: InputStream, bytes: ByteArray, size: Int): Int = try {
        input.read(bytes, 0, size)
    } catch (error: IOException) {
        throw LanTransferInterruptedException(error)
    }

    /** 响应文件仍由原来的流读取，仅包装网络写入与刷新。 */
    fun output(target: OutputStream): OutputStream = object : OutputStream() {
        override fun write(value: Int) = network { target.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) = network { target.write(bytes, offset, length) }
        override fun flush() = network { target.flush() }
        private inline fun network(action: () -> Unit) {
            try { action() } catch (error: IOException) { throw LanTransferInterruptedException(error) }
        }
    }
}
