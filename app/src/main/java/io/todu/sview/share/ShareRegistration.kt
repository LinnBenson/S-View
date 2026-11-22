package io.todu.sview.share

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.todu.sview.data.Project
import io.todu.sview.data.ShareSource
import io.todu.sview.wear.WatchDevice

/** 仅控制系统分享别名；URL 启动入口始终可用，手表不注册分享入口。 */
internal object ShareRegistration {
    fun update(context: Context, projects: List<Project>) {
        val enabled = !WatchDevice.isWatch(context) && projects.any { it.shareSource != ShareSource.DISABLED }
        val component = ComponentName(context.packageName, "${context.packageName}.share.ShareReceiver")
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (context.packageManager.getComponentEnabledSetting(component) != state)
            context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
