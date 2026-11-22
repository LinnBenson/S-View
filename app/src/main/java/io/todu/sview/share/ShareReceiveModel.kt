package io.todu.sview.share

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*

/** 配置变更保留接收快照，真正结束 Activity 时才清理。 */
class ShareReceiveModel(application: Application) : AndroidViewModel(application) {
    internal var input by mutableStateOf<ShareInput?>(null); private set
    var payload by mutableStateOf<SharedPayload?>(null); private set
    var error by mutableStateOf<String?>(null)
    var selectedId: String? = null
    private var started = false
    fun receive(intent: Intent) {
        if (started) return
        started = true
        viewModelScope.launch {
            var staged: SharedPayload? = null
            try {
                val value = ShareInput.parse(intent, io.todu.sview.wear.WatchDevice.isWatch(getApplication()))
                input = value
                withContext(Dispatchers.IO) { staged = ShareInbox.receive(getApplication(), value) }
                payload = staged
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "无法接收分享" }
            finally { if (payload == null) withContext(NonCancellable + Dispatchers.IO) { staged?.close() } }
        }
    }
    override fun onCleared() {
        io.todu.sview.runtime.cleanupResource("分享缓存") { payload?.close() }
        super.onCleared()
    }
}
