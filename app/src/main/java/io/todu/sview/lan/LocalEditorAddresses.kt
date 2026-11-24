package io.todu.sview.lan

import java.net.Inet4Address
import java.net.NetworkInterface

/** HTTP 与 FTP 共用启动时的局域网地址，不绑定 IPv6 或通配地址。 */
internal object LocalEditorAddresses {
    /** 包含 Wi-Fi、热点等可用网卡的私有 IPv4；网络变化后重新启动服务刷新地址。 */
    fun hosts(): List<Inet4Address> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .distinct()
}
