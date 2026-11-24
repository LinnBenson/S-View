package io.todu.sview.runtime.scan

import org.json.JSONArray
import org.json.JSONObject

/** 校验并展开单主机扫描参数，重复端口只扫描一次。 */
internal data class PortScanOptions(val host: String, val ports: List<Int>, val timeout: Int, val concurrency: Int) {
    companion object {
        fun parse(args: JSONObject, watch: Boolean): PortScanOptions {
            val host = args.opt("host") as? String ?: throw IllegalArgumentException("host 必须是主机地址")
            require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it in "/?#@[]" }) {
                "host 必须是 IP 地址或域名，不接受 URL；IPv6 地址不加方括号"
            }
            val ports = sortedSetOf<Int>()
            fun add(value: Int) { require(value in 1..65535) { "端口范围必须为 1～65535" }; ports.add(value) }
            val input = args.opt("ports")
            when {
                input is JSONArray -> {
                    require(input.length() <= 65535) { "端口列表过长" }
                    for (index in 0 until input.length()) add(integer(input.get(index), "ports"))
                }
                input is String -> {
                    require(input.length <= 400000) { "端口列表过长" }
                    input.split(',').forEach { part ->
                        val token = part.trim()
                        require(token.matches(Regex("[0-9]+(-[0-9]+)?"))) { "端口格式无效" }
                        val range = token.split('-').map { it.toIntOrNull() ?: throw IllegalArgumentException("端口无效") }
                        val first = range.first()
                        val last = range.last()
                        require(first in 1..65535 && last in first..65535) { "端口范围必须为 1～65535，起始值不能大于结束值" }
                        (first..last).forEach(::add)
                    }
                }
                else -> throw IllegalArgumentException("ports 必须是整数数组或端口范围字符串")
            }
            require(ports.isNotEmpty()) { "至少指定一个端口" }
            val timeout = if (args.has("timeout")) integer(args.get("timeout"), "timeout") else 1000
            val concurrency = if (args.has("concurrency")) integer(args.get("concurrency"), "concurrency") else if (watch) 4 else 16
            require(timeout in 1..60000) { "timeout 必须为 1～60000 毫秒" }
            require(concurrency in 1..32) { "concurrency 必须为 1～32" }
            return PortScanOptions(host, ports.toList(), timeout, minOf(concurrency, ports.size))
        }

        private fun integer(value: Any, name: String): Int {
            require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) {
                "$name 必须使用整数"
            }
            return value.toInt()
        }
    }
}
