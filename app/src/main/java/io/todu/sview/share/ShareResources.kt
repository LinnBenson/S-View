package io.todu.sview.share

import java.net.Socket

/** 分享文件只属于当前资源服务，不暴露临时路径，沿用项目会话认证。 */
internal object ShareResources {
    fun serve(socket: Socket, target: String, method: String, headers: Map<String, String>, payload: SharedPayload?) {
        val origin = "http://127.0.0.1:${socket.localPort}"
        require(method == "GET" && headers["host"] == "127.0.0.1:${socket.localPort}") { "请求无效" }
        require(headers["origin"] == null || headers["origin"] == origin) { "不允许跨来源读取" }
        require(headers["sec-fetch-site"] != "cross-site") { "不允许跨来源读取" }
        val output = socket.getOutputStream()
        if (target == "/__sview_share") {
            val bytes = (payload?.metadata() ?: SharedPayload.empty()).toString().toByteArray()
            output.write(header("application/json; charset=utf-8", bytes.size.toLong()).toByteArray())
            output.write(bytes)
        } else {
            val index = target.removePrefix("/__sview_share/").toIntOrNull() ?: error("文件索引无效")
            val file = requireNotNull(payload) { "没有分享文件" }.file(index)
            file.inputStream().use { input ->
                output.write(header("application/octet-stream", file.length()).toByteArray())
                input.copyTo(output, 65536)
            }
        }
    }
    private fun header(type: String, length: Long): String =
        "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: $length\r\nCache-Control: no-store\r\n" +
        "X-Content-Type-Options: nosniff\r\nContent-Security-Policy: sandbox; default-src 'none'\r\n" +
        "Cross-Origin-Resource-Policy: same-origin\r\nConnection: close\r\n\r\n"
}
