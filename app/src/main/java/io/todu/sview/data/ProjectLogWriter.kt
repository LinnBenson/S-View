package io.todu.sview.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.LocalDateTime

/** 保留接收时间，合并写盘不会把一批消息都标为落盘时间。 */
data class ProjectLogRecord(val projectId: String, val message: String, val time: LocalDateTime = LocalDateTime.now())

/** 有消息才启动短暂合并窗口；空闲时挂起，不设置周期唤醒。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectLogWriter(scope: CoroutineScope, private val write: suspend (List<ProjectLogRecord>) -> Unit) {
    private sealed interface Command {
        data class Entry(val record: ProjectLogRecord) : Command
        data class Flush(val done: CompletableDeferred<Unit>) : Command
    }
    private val queue = Channel<Command>(64, onUndeliveredElement = { command ->
        if (command is Command.Flush) command.done.cancel()
    })
    private val worker = scope.launch {
        for (first in queue) {
            if (first is Command.Flush) { first.done.complete(Unit); continue }
            val batch = mutableListOf((first as Command.Entry).record)
            val deadline = System.nanoTime() + 150_000_000L
            var barrier: Command.Flush? = null
            while (batch.size < 32) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                when (val next = select<Command?> { queue.onReceive { it }; onTimeout(remaining) { null } }) {
                    is Command.Entry -> batch.add(next.record)
                    is Command.Flush -> { barrier = next; break }
                    null -> break
                }
            }
            try { write(batch); barrier?.done?.complete(Unit) }
            catch (cancelled: CancellationException) { barrier?.done?.cancel(); throw cancelled }
            catch (failure: Exception) { barrier?.done?.completeExceptionally(failure) }
        }
    }.also { job -> job.invokeOnCompletion { queue.cancel() } }

    /** 满载时不阻塞网页线程，也不无限堆积日志。 */
    fun append(id: String, message: String) { queue.trySend(Command.Entry(ProjectLogRecord(id, message.take(16_384)))) }

    /** 屏障前的消息全部落盘后才继续读取、导出、清空或关闭。 */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        val completion = worker.invokeOnCompletion { done.cancel() }
        try {
            queue.send(Command.Flush(done))
            done.await()
        } finally { completion.dispose() }
    }
}
