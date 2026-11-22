package io.todu.sview.runtime

import io.todu.sview.data.ProjectRepository
import io.todu.sview.runtime.storage.StoragePaths
import java.net.Socket
import io.todu.sview.storage.DeviceResourceMime
import java.net.URLDecoder

/** 使用已有设备权限与路径校验；直接流式输出图片，不创建 Blob 或复制文件。 */
internal object DeviceImageResource {
    fun serve(repository: ProjectRepository, projectId: String, socket: Socket, target: String,
        method: String, headers: Map<String, String>) {
        val origin = "http://127.0.0.1:${socket.localPort}"
        require(headers["host"] == "127.0.0.1:${socket.localPort}") { "访问地址不匹配" }
        require(headers["origin"] == null || headers["origin"] == origin) { "不允许跨来源访问" }
        require(headers["sec-fetch-site"] != "cross-site") { "不允许跨来源访问" }
        require(method == "GET" || method == "HEAD") { "不支持的请求" }
        val encoded = target.substringAfter('?', "").split('&').single { it.startsWith("path=") }.substringAfter('=')
        val path = URLDecoder.decode(encoded, "UTF-8")
        require(path.startsWith("device:/") && path.length <= 4096) { "设备路径无效" }
        val file = StoragePaths(repository.root(projectId), repository::deviceStorageRoot).resolve(path).file
        require(file.isFile) { "文件不存在" }
        val type = DeviceResourceMime.type(file)
        require(type.startsWith("image/")) { "仅支持图片资源" }
        file.inputStream().use { input ->
            val output = socket.getOutputStream()
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${file.length()}\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
                "Content-Security-Policy: sandbox; default-src 'none'; style-src 'unsafe-inline'\r\n" +
                "Cross-Origin-Resource-Policy: same-origin\r\nConnection: close\r\n\r\n").toByteArray())
            if (method == "GET") input.copyTo(output, 32768)
        }
    }
}
