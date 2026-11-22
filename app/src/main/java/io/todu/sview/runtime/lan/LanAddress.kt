package io.todu.sview.runtime.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** 默认只选当前 Wi-Fi；显式地址不允许通过 DNS 解析到其他设备。 */
internal object LanAddress {
    @Suppress("DEPRECATION")
    fun resolve(context: Context, value: String): InetAddress {
        val all = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
        if (value.isEmpty()) {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            return manager.allNetworks.firstNotNullOfOrNull { network ->
                if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                    manager.getLinkProperties(network)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()
                else null
            } ?: error("当前没有可用的 Wi-Fi IPv4 地址，请指定 server")
        }
        if (value == "0.0.0.0") return InetAddress.getByName(value)
        if (value.endsWith(".*")) {
            val prefix = value.dropLast(1)
            require(prefix.dropLast(1).split('.').all { it.toIntOrNull() in 0..255 }) { "server 地址前缀无效" }
            return all.firstOrNull { it.hostAddress?.startsWith(prefix) == true } ?: error("没有匹配 server 的网卡")
        }
        require(value.split('.').size == 4 && value.split('.').all { it.toIntOrNull() in 0..255 }) { "server 应为本机 IPv4 地址或地址前缀" }
        return all.firstOrNull { it.hostAddress == value } ?: error("server 不是本机可用网卡地址")
    }
}
