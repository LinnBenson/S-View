package io.todu.sview.ftp

import java.io.Closeable
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** 只绑定启动时的局域网 IPv4 地址，最多两个会话，不创建轮询或唤醒锁。 */
class FtpServer(hosts: List<Inet4Address>, port: Int, private val password: String,
                private val access: (FtpRequest) -> FtpResult) : Closeable {
    private val listeners = mutableListOf<ServerSocket>()
    private val sessions = ConcurrentHashMap.newKeySet<FtpSession>()
    private val permits = Semaphore(2)
    private val workers = Executors.newFixedThreadPool(2) { task -> Thread(task, "FTP 文件传输").apply { isDaemon = true } }
    @Volatile private var closed = false

    init {
        try {
            hosts.forEach { host -> listeners.add(ServerSocket(port, 2, host)) }
            listeners.forEach { listener ->
                Thread({ accept(listener) }, "FTP 连接监听").apply { isDaemon = true; start() }
            }
        } catch (error: Exception) { close(); throw error }
    }

    private fun accept(listener: ServerSocket) {
        while (!closed) {
            val socket = try { listener.accept() } catch (_: Exception) { return }
            if (closed || !permits.tryAcquire()) { reject(socket); continue }
            val session = FtpSession(socket, password, access)
            sessions.add(session)
            try {
                workers.execute {
                    try { if (!closed) session.run() }
                    finally { session.close(); sessions.remove(session); permits.release() }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                session.close(); sessions.remove(session); permits.release()
            }
        }
    }

    private fun reject(socket: Socket) {
        socket.use { runCatching { it.getOutputStream().write("421 Too many connections\r\n".toByteArray()) } }
    }

    override fun close() {
        closed = true
        listeners.forEach { runCatching { it.close() } }
        sessions.forEach { it.close() }
        workers.shutdownNow()
    }
}
