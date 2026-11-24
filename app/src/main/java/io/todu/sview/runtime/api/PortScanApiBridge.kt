package io.todu.sview.runtime.api

import android.content.Context
import io.todu.sview.runtime.scan.PortScanOptions
import io.todu.sview.runtime.scan.PortScanTask
import io.todu.sview.wear.WatchDevice
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Semaphore

/** 扫描句柄仅在所属运行窗口有效；控制请求不排在结果读取后面。 */
internal class PortScanApiBridge(context: Context) : Closeable {
    private val watch = WatchDevice.isWatch(context)
    private val tasks = mutableMapOf<String, PortScanTask>()
    private var closed = false

    fun invoke(request: JSONObject): JSONObject = try {
        val args = request.getJSONObject("args")
        val id = args.getString("id")
        val value = when (request.getString("method")) {
            "network.scanPorts" -> synchronized(tasks) {
                check(!closed) { "运行窗口已关闭" }
                require(id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) { "扫描标识无效" }
                if (tasks.isNotEmpty()) throw ApiException("BUSY", "当前窗口已有扫描任务，请先结束该任务")
                val task = PortScanTask(PortScanOptions.parse(args, watch))
                if (!slots.tryAcquire()) throw ApiException("BUSY", "同时扫描的任务过多，请稍后重试")
                tasks[id] = task
                task.completion.whenComplete { _, _ -> slots.release() }
                task.start()
                JSONObject.NULL
            }
            "network.scanPorts.next" -> {
                val task = synchronized(tasks) { tasks[id] } ?: throw ApiException("NOT_FOUND", "扫描任务已结束")
                task.next().also { batch ->
                    if (batch.has("summary")) synchronized(tasks) { tasks.remove(id) }
                }
            }
            "network.scanPorts.close" -> {
                val task = synchronized(tasks) { tasks[id] }
                task?.stop()
                task?.completion?.get()
                if (args.optBoolean("discard")) synchronized(tasks) { tasks.remove(id) }
                JSONObject.NULL
            }
            else -> throw IllegalArgumentException("接口不存在")
        }
        JSONObject().put("ok", true).put("value", value)
    } catch (error: Exception) { ApiErrors.failure(error) }

    companion object {
        private val slots = Semaphore(4, true)
    }

    override fun close() {
        synchronized(tasks) {
            closed = true
            tasks.values.forEach { it.stop() }
            tasks.clear()
        }
    }
}
