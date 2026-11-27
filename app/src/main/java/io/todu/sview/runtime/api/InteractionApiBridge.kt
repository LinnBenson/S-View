package io.todu.sview.runtime.api

import io.todu.sview.runtime.cleanupResource
import android.os.Handler
import android.os.Looper
import io.todu.sview.data.WindowDimension
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException

/** 关闭请求先发送响应，再开始关闭动画，避免在写回响应前销毁资源服务。 */
internal class InteractionApiBridge(private val resize: (WindowDimension, WindowDimension, Boolean, (Result<Unit>) -> Unit) -> Unit, private val dismiss: () -> Unit,
    private val location: (io.todu.sview.data.WindowLocation, Boolean, (Result<Unit>) -> Unit) -> Unit,
    private val removeClose: () -> Unit,
    private val margin: (io.todu.sview.data.WindowMargins, Boolean, (Result<Unit>) -> Unit) -> Unit,
    private val cornerRadius: (Double, Boolean, (Result<Unit>) -> Unit) -> Unit,
    private val preview: (String) -> Unit,
    private val backgroundWindow: (Boolean, Boolean) -> CompletableFuture<Boolean>,
    private val exitApplication: () -> Unit) : Closeable {
    private val main = MainThreadCalls()
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)
    private val dismissRequested = AtomicBoolean(false)
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<Unit>>()

    fun invoke(json: JSONObject): JSONObject = try {
        val request = InteractionApiRequest.parse(json)
        val value = when (request) {
            is InteractionApiRequest.Background -> {
                val result = main.invoke { backgroundWindow(request.enabled, request.dynamic) } as CompletableFuture<*>
                try { result.get(10, TimeUnit.SECONDS) } catch (error: ExecutionException) { throw (error.cause as? Exception ?: error) }
            }
            is InteractionApiRequest.Preview -> preview(request.path)
            is InteractionApiRequest.CornerRadius -> changeAndWait { cornerRadius(request.cornerRadius, request.persist, it) }
            is InteractionApiRequest.Margin -> changeAndWait { margin(request.margins, request.persist, it) }
            is InteractionApiRequest.Resize -> changeAndWait { resize(request.width, request.height, request.persist, it) }
            is InteractionApiRequest.Location -> changeAndWait { location(request.location, request.persist, it) }
            InteractionApiRequest.RemoveClose -> main.invoke { removeClose(); JSONObject.NULL }
            InteractionApiRequest.CloseThis, InteractionApiRequest.Close -> main.invoke { JSONObject.NULL }
        }
        JSONObject().put("ok", true).put("value", if (request is InteractionApiRequest.Background) value else JSONObject.NULL)
    } catch (error: Exception) { ApiErrors.failure(error) }

    /** 持久化需等项目配置写入完成，再把成功结果回复给脚本。 */
    private fun changeAndWait(apply: ((Result<Unit>) -> Unit) -> Unit) {
        val completion = CompletableFuture<Unit>()
        pending.add(completion)
        try {
            main.invoke {
                apply { result ->
                    result.fold({ completion.complete(Unit) }, completion::completeExceptionally)
                }
                JSONObject.NULL
            }
            completion.get(15, TimeUnit.SECONDS)
        } catch (error: ExecutionException) { throw (error.cause as? Exception ?: error) }
        finally { pending.remove(completion); completion.cancel(false) }
    }

    fun responseSent(request: JSONObject, response: JSONObject) {
        val method = request.optString("method")
        if ((method == "interaction.close" || method == "interaction.closeThis") &&
            response.optBoolean("ok") && dismissRequested.compareAndSet(false, true)) {
            handler.post {
                if (active.get()) {
                    if (method == "interaction.closeThis") exitApplication() else dismiss()
                }
            }
        }
    }

    override fun close() {
        active.set(false)
        pending.forEach { result -> cleanupResource("尺寸请求") { result.cancel(false) } }
        pending.clear()
        cleanupResource("交互调用队列") { main.close() }
        cleanupResource("交互回调") { handler.removeCallbacksAndMessages(null) }
    }
}
