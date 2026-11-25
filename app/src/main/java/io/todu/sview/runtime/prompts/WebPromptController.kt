package io.todu.sview.runtime.prompts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.todu.sview.data.ProjectRepository
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import java.io.Closeable
import java.util.UUID

/** 每个运行窗口独立保存提示状态，由窗口自身绘制弹层。 */
internal class WebPromptController(private val repository: ProjectRepository,
    private val projectId: String) : PromptDelegate, Closeable {
    var active by mutableStateOf<WebPromptRequest?>(null); private set
    private var closed = false

    private fun show(prompt: PromptDelegate.BasePrompt): GeckoResult<PromptDelegate.PromptResponse> {
        if (closed) return GeckoResult.fromValue(prompt.dismiss())
        active?.cancel()
        val request = WebPromptRequest(prompt, repository, projectId) { active = null }
        active = request
        return request.result
    }

    override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt) = show(prompt)
    override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt) = show(prompt)
    override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt) = show(prompt)
    override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt) = show(prompt)
    override fun onColorPrompt(session: GeckoSession, prompt: PromptDelegate.ColorPrompt) = show(prompt)
    override fun onDateTimePrompt(session: GeckoSession, prompt: PromptDelegate.DateTimePrompt) = show(prompt)
    override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptDelegate.PromptResponse> =
        if (prompt.type == PromptDelegate.FilePrompt.Type.FOLDER) GeckoResult.fromValue(prompt.dismiss()) else show(prompt)

    /** 会话关闭先撤销提示，避免结果回传到已销毁的网页。 */
    override fun close() {
        closed = true
        active?.cancel()
    }
}

/** 主线程内保存待处理请求；确认、取消、页面导航和窗口销毁共用一次性完成出口。 */
internal class WebPromptRequest(initial: PromptDelegate.BasePrompt, val repository: ProjectRepository,
    val projectId: String, private val onFinished: () -> Unit) : PromptDelegate.PromptInstanceDelegate {
    val id = UUID.randomUUID().toString()
    val result = GeckoResult<PromptDelegate.PromptResponse>()
    var prompt by mutableStateOf(initial); private set
    private var finished = false

    init { initial.setDelegate(this) }

    fun complete(response: () -> PromptDelegate.PromptResponse) {
        if (finished) return
        // 内核已撤销时不再调用 confirm/dismiss，防止重复完成。
        val value = if (prompt.isComplete) null else response()
        finished = true
        prompt.setDelegate(null)
        onFinished()
        result.complete(value)
    }

    fun cancel() = complete { prompt.dismiss() }

    override fun onPromptDismiss(prompt: PromptDelegate.BasePrompt) { cancel() }
    override fun onPromptUpdate(prompt: PromptDelegate.BasePrompt) {
        if (finished) return
        this.prompt.setDelegate(null)
        this.prompt = prompt
        prompt.setDelegate(this)
    }
}
