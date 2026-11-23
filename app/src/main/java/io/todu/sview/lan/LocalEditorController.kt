package io.todu.sview.lan

/** 两种电脑编辑入口共用表单；传输协议与服务生命周期由各自控制器负责。 */
interface LocalEditorController {
    val running: Boolean
    val busy: Boolean
    val addresses: List<String>
    val message: String?
    val savedPort: String
    val statusText: String
    suspend fun savedPassword(): String
    suspend fun start(portText: String, password: String)
    fun stop()
}
