package io.todu.sview.runtime.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** 按需读取系统向应用公开的网卡，不扫描网络、不读取 MAC 地址。 */
internal object NetworkInterfaceApiBridge {
    fun invoke(): JSONObject = try {
        val result = JSONArray()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().sortedBy { it.index }
        for (network in interfaces) {
            val addresses = JSONArray()
            for (binding in network.interfaceAddresses) {
                val address = binding.address ?: continue
                addresses.put(JSONObject()
                    .put("address", address.hostAddress)
                    .put("family", if (address is Inet4Address) "IPv4" else "IPv6")
                    .put("prefixLength", binding.networkPrefixLength.toInt()))
            }
            result.put(JSONObject()
                .put("name", network.name)
                .put("displayName", network.displayName ?: network.name)
                .put("index", network.index)
                .put("isUp", network.isUp)
                .put("isLoopback", network.isLoopback)
                .put("isVirtual", network.isVirtual)
                .put("mtu", network.mtu)
                .put("addresses", addresses))
        }
        JSONObject().put("ok", true).put("value", result)
    } catch (error: Exception) {
        ApiErrors.failure(error)
    }
}
