package io.todu.sview.runtime.network

import java.io.IOException

/** 下载落盘不等待网页；只保留最新进度，结束和失败立即唤醒等待方。 */
internal class DownloadProgress {
    data class Snapshot(val loaded: Long, val ended: Boolean)
    private val lock = Object()
    private var latest = 0L
    private var pending = false
    private var ended = false
    private var failure: Exception? = null
    private var publishedAt = 0L

    fun publish(loaded: Long, complete: Boolean = false) = synchronized(lock) {
        if (failure != null || ended) return@synchronized
        latest = loaded
        val now = System.nanoTime()
        if (complete || now - publishedAt >= 100_000_000L) {
            publishedAt = now
            pending = true
            ended = complete
            lock.notifyAll()
        }
    }

    fun take(): Snapshot = synchronized(lock) {
        while (!pending && !ended && failure == null) lock.wait()
        failure?.let { throw it }
        pending = false
        Snapshot(latest, ended)
    }

    fun cancel(error: Exception = IOException("下载已取消")) = synchronized(lock) {
        failure = error
        lock.notifyAll()
    }
}
