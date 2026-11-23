package io.todu.sview.ftp

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 每个会话独占控制连接和一个被动端口；数据连接只接受同一客户端。 */
internal class FtpSession(private val control: Socket, private val password: String,
                          private val access: (FtpRequest) -> FtpResult) : Closeable {
    @Volatile private var passive: ServerSocket? = null
    @Volatile private var data: Socket? = null
    @Volatile private var closed = false
    private var directory = "/"
    private var username = ""
    private var authenticated = false
    private var failures = 0
    private var binary = true
    private var renameFrom: String? = null
    private val output by lazy { control.getOutputStream().bufferedWriter(Charsets.UTF_8) }

    fun run() {
        try {
            control.soTimeout = 120_000
            val input = control.getInputStream().buffered()
            reply(220, "S View FTP")
            while (!closed) {
                val line = commandLine(input) ?: return
                val command = line.substringBefore(' ').uppercase(Locale.ROOT)
                val argument = line.substringAfter(' ', "")
                if (command != "RNTO" && command != "RNFR") renameFrom = null
                try { command(command, argument) }
                catch (error: Exception) {
                    if (closed) return
                    reply(550, (error.message ?: "Operation failed").replace('\r', ' ').replace('\n', ' ').take(200))
                }
            }
        } catch (_: Exception) {
            // 客户端断开、空闲超时或服务停止均由统一关闭路径回收资源。
        } finally { close() }
    }

    private fun commandLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return null
            if (next == 10) return bytes.toString("UTF-8").removeSuffix("\r")
            require(bytes.size() < 4096) { "Command too long" }
            bytes.write(next)
        }
    }

    private fun reply(code: Int, text: String) { output.write("$code $text\r\n"); output.flush() }

    private fun command(command: String, argument: String) {
        if (sessionCommand(command, argument)) return
        if (!authenticated) { reply(530, "Login required"); return }
        when (command) {
            "PWD", "XPWD" -> reply(257, "\"${directory.replace("\"", "\"\"")}\"")
            "CWD", "XCWD", "CDUP", "XCUP" -> {
                val path = path(if (command == "CDUP" || command == "XCUP") ".." else argument)
                require(access(FtpRequest("stat", path)).entries.single().directory) { "Not a directory" }
                directory = path; reply(250, "Directory changed")
            }
            "TYPE" -> {
                val type = argument.uppercase(Locale.ROOT)
                if (type in setOf("I", "A", "A N")) { binary = type == "I"; reply(200, "Type selected") }
                else reply(504, "Unsupported type")
            }
            "MODE" -> reply(if (argument == "S") 200 else 504, "Stream mode")
            "STRU" -> reply(if (argument == "F") 200 else 504, "File structure")
            "PASV", "EPSV" -> openPassive(command, argument)
            "LIST", "NLST", "MLSD" -> list(command, argument)
            "MLST" -> {
                val path = path(argument)
                val entry = access(FtpRequest("stat", path)).entries.single()
                output.write("250-Listing\r\n ${facts(entry)} $path\r\n250 End\r\n"); output.flush()
            }
            "SIZE", "MDTM" -> {
                val entry = access(FtpRequest("stat", path(argument))).entries.single()
                require(!entry.directory) { "Not a file" }
                reply(213, if (command == "SIZE") entry.size.toString() else timestamp.format(Instant.ofEpochMilli(entry.modified)))
            }
            "RETR" -> {
                val bytes = requireNotNull(access(FtpRequest("read", path(argument))).bytes)
                transfer { it.getOutputStream().write(if (binary) bytes else ascii(bytes, uploading = false)) }
            }
            "STOR" -> {
                val target = path(argument)
                transfer { socket ->
                    val bytes = readFile(socket.getInputStream())
                    check(!closed) { "Service stopped" }
                    access(FtpRequest("write", target, bytes = if (binary) bytes else ascii(bytes, uploading = true)))
                }
            }
            "MKD", "XMKD" -> { val path = path(argument); access(FtpRequest("mkdir", path)); reply(257, "\"${path.replace("\"", "\"\"")}\"") }
            "DELE", "RMD", "XRMD" -> {
                access(FtpRequest(if (command == "DELE") "delete" else "rmdir", path(argument))); reply(250, "Deleted")
            }
            "RNFR" -> { val path = path(argument); access(FtpRequest("stat", path)); renameFrom = path; reply(350, "Destination required") }
            "RNTO" -> {
                val source = renameFrom; renameFrom = null
                if (source == null) reply(503, "RNFR required")
                else { access(FtpRequest("rename", source, path(argument))); reply(250, "Renamed") }
            }
            "ABOR" -> { closeData(); reply(226, "Aborted") }
            "ALLO" -> reply(202, "Not required")
            else -> reply(502, "Command not supported")
        }
    }

    /** 登录和能力协商无需访问工作区。 */
    private fun sessionCommand(command: String, argument: String): Boolean {
        when (command) {
            "QUIT" -> { reply(221, "Goodbye"); close(); return true }
            "USER" -> { username = argument; authenticated = false; closeData(); reply(331, "Password required"); return true }
            "PASS" -> {
                authenticated = username == "sview" && MessageDigest.isEqual(argument.toByteArray(), password.toByteArray())
                reply(if (authenticated) 230 else 530, if (authenticated) "Logged in" else "Login incorrect")
                if (!authenticated && ++failures >= 3) close()
                return true
            }
            "FEAT" -> {
                output.write("211-Features\r\n UTF8\r\n EPSV\r\n SIZE\r\n MDTM\r\n MLST type*;size*;modify*;\r\n211 End\r\n"); output.flush(); return true
            }
            "SYST" -> { reply(215, "UNIX Type: L8"); return true }
            "OPTS" -> { reply(if (argument.uppercase(Locale.ROOT) == "UTF8 ON") 200 else 501, "UTF8"); return true }
            "NOOP" -> { reply(200, "OK"); return true }
        }
        return false
    }

    /** 目录列表使用 UTF-8；机器可读列表提供类型、大小及 UTC 修改时间。 */
    private fun list(command: String, argument: String) {
        val requested = if (command == "LIST" && argument.startsWith('-')) argument.substringAfter(' ', "") else argument
        val entries = access(FtpRequest("list", path(requested))).entries
        transfer { socket ->
            val text = entries.joinToString("\r\n", postfix = if (entries.isEmpty()) "" else "\r\n") {
                when (command) {
                    "NLST" -> it.name
                    "MLSD" -> facts(it) + " " + it.name
                    else -> "${if (it.directory) 'd' else '-'}rw-r--r-- 1 sview sview ${it.size} ${listingDate.format(Instant.ofEpochMilli(it.modified))} ${it.name}"
                }
            }
            socket.getOutputStream().write(text.toByteArray())
        }
    }

    /** ASCII 传输只转换换行，二进制传输始终保留原始字节。 */
    private fun ascii(bytes: ByteArray, uploading: Boolean): ByteArray {
        val result = ByteArrayOutputStream()
        for (index in bytes.indices) {
            val byte = bytes[index].toInt() and 255
            if (uploading && byte == 13 && bytes.getOrNull(index + 1) == 10.toByte()) continue
            if (!uploading && byte == 10 && bytes.getOrNull(index - 1) != 13.toByte()) result.write(13)
            result.write(byte)
        }
        return result.toByteArray()
    }

    /** FTP 的上级目录语义只作用于虚拟根，不能跳到应用私有目录。 */
    private fun path(argument: String): String {
        require(argument.none { it < ' ' || it == '\\' } && argument.length <= 1024) { "Invalid path" }
        val parts = if (argument.startsWith('/')) mutableListOf() else directory.split('/').filter { it.isNotEmpty() }.toMutableList()
        argument.split('/').forEach {
            when (it) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts.add(it) }
        }
        return "/" + parts.joinToString("/")
    }

    private fun openPassive(command: String, argument: String) {
        closeData()
        if (command == "EPSV" && argument !in setOf("", "1")) { reply(522, "Use IPv4"); return }
        val listener = ServerSocket(0, 1, control.localAddress).apply { soTimeout = 15_000 }
        passive = listener
        if (closed) { closeData(); return }
        if (command == "EPSV") reply(229, "Entering Extended Passive Mode (|||${listener.localPort}|)")
        else {
            val host = control.localAddress.address.joinToString(",") { (it.toInt() and 255).toString() }
            reply(227, "Entering Passive Mode ($host,${listener.localPort / 256},${listener.localPort % 256})")
        }
    }

    private fun transfer(action: (Socket) -> Unit) {
        val listener = passive
        if (listener == null) { reply(425, "Use PASV or EPSV first"); return }
        reply(150, "Opening data connection")
        try {
            val socket = listener.accept()
            data = socket
            check(!closed && socket.inetAddress == control.inetAddress) { "Invalid data connection" }
            socket.soTimeout = 30_000
            action(socket)
            closeData()
            reply(226, "Transfer complete")
        } catch (error: Exception) {
            closeData()
            if (!closed) reply(426, (error.message ?: "Transfer failed").replace('\r', ' ').replace('\n', ' ').take(200))
        }
    }

    private fun closeData() {
        runCatching { passive?.close() }; passive = null
        runCatching { data?.close() }; data = null
    }

    override fun close() { closed = true; closeData(); runCatching { control.close() } }

    private fun facts(entry: FtpEntry) = "type=${if (entry.directory) "dir" else "file"};size=${entry.size};modify=${timestamp.format(Instant.ofEpochMilli(entry.modified))};"

    companion object {
        private val timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)
        private val listingDate = DateTimeFormatter.ofPattern("MMM dd  yyyy", Locale.US).withZone(ZoneOffset.UTC)

        /** 单文件有明确上限，防止上传或磁盘文件变化导致内存无限增长。 */
        fun readFile(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= FtpWorkspace.MAX_FILE_BYTES) { "File exceeds 16 MB" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
