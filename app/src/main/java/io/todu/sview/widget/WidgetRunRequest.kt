package io.todu.sview.widget

import java.util.UUID

/** 只有应用内部的小部件入口能签发自动运行请求，公开首页不接受任意项目 ID。 */
internal object WidgetRunRequest {
    private var pending: Pair<String, String>? = null
    fun issue(id: String): String = UUID.randomUUID().toString().also { pending = it to id }
    fun consume(token: String): String? {
        val request = pending ?: return null
        if (request.first != token) return null
        pending = null
        return request.second
    }
}
