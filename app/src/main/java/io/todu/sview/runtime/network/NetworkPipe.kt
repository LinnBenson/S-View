package io.todu.sview.runtime.network

import java.io.IOException
import java.util.concurrent.CompletableFuture

/** 单块握手通道；生产者等待消费完成，避免流上传和下载堆积内存。 */
internal class NetworkPipe {
    class Block(val bytes: ByteArray) {
        val consumed = CompletableFuture<Unit>()
    }
    private val lock = Object()
    private var block: Block? = null
    private var claimed = false
    private var ended = false
    private var failure: Exception? = null

    fun put(bytes: ByteArray) {
        val next = Block(bytes)
        synchronized(lock) {
            while (block != null && failure == null && !ended) lock.wait()
            failure?.let { throw it }
            check(!ended) { "数据流已结束" }
            block = next
            lock.notifyAll()
        }
        next.consumed.get()
    }

    fun take(): Block? = synchronized(lock) {
        while ((block == null || claimed) && !ended && failure == null) lock.wait()
        failure?.let { throw it }
        // 领取即标记为已取，避免下一次读取在确认消费前重复取得同一块。
        block.also { if (it != null) claimed = true }
    }

    fun consumed(value: Block) = synchronized(lock) {
        if (block === value) { block = null; claimed = false }
        value.consumed.complete(Unit)
        lock.notifyAll()
    }

    fun end() = synchronized(lock) { ended = true; lock.notifyAll() }

    fun cancel(error: Exception = IOException("请求已取消")) = synchronized(lock) {
        failure = error
        block?.consumed?.completeExceptionally(error)
        block = null
        ended = true
        lock.notifyAll()
    }
}
