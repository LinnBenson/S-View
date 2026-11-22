package io.todu.sview.data

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** 工作区与悬浮运行共用状态和写入队列，避免两个仓库实例覆盖项目配置。 */
object WorkspaceOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    fun model(application: Application): WorkspaceModel =
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[WorkspaceModel::class.java]
}
