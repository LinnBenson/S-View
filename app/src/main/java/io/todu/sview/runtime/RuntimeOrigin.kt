package io.todu.sview.runtime

import android.content.Context
import java.net.BindException

/** 本地端口只分配一次并落盘；不能因端口被占用悄悄换来源，导致项目存储失联。 */
internal object RuntimeOrigin {
    /** 在 IO 线程创建服务；首次运行在返回前保存端口。 */
    fun open(context: Context, factory: (Int) -> ProjectServer): ProjectServer {
        val preferences = context.getSharedPreferences("runtime_origin", Context.MODE_PRIVATE)
        val port = preferences.getInt("port", 0)
        val server = try { factory(port) }
        catch (error: BindException) {
            throw IllegalStateException("本地运行端口被占用，请稍后重试或重启设备", error)
        }
        if (port == 0 && !preferences.edit().putInt("port", server.port).commit()) {
            server.close()
            error("无法保存运行地址，请检查存储空间")
        }
        return server
    }
}
