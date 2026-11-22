package io.todu.sview.share

import android.content.Context
import android.provider.OpenableColumns
import io.todu.sview.storage.TemporaryFiles
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/** 在临时授权仍有效时复制内容，只接受 content URI，按实际读取量限制总大小。 */
internal object ShareInbox {
    suspend fun receive(context: Context, input: ShareInput): SharedPayload {
        if (input.uris.isEmpty()) return SharedPayload(input.text, input.title, emptyList())
        val store = TemporaryFiles.get(context)
        val directory = store.createDirectory(context.cacheDir, "share-inbox-")
        try {
            val resolver = context.contentResolver
            var total = 0L
            val files = input.uris.mapIndexed { index, uri ->
                coroutineContext.ensureActive()
                require(uri.scheme == "content" && uri.authority != null && uri.authority !in setOf("${context.packageName}.device-files", "${context.packageName}.install-files")) { "分享文件地址无效" }
                var name = "shared-${index + 1}"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && !cursor.isNull(column)) name = cursor.getString(column)
                        val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (size >= 0 && !cursor.isNull(size)) require(cursor.getLong(size) <= SharedPayload.MAX_BYTES - total) { "分享文件总大小不能超过 32 MB" }
                    }
                }
                name = name.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.take(240).ifBlank { "shared-${index + 1}" }
                val file = File(directory, index.toString())
                requireNotNull(resolver.openInputStream(uri)) { "无法读取分享文件" }.use { source ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= SharedPayload.MAX_BYTES) { "分享文件总大小不能超过 32 MB" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val type = resolver.getType(uri)?.takeIf { it.length <= 256 && '/' in it && '*' !in it }
                    ?: java.net.URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
                SharedPayload.SharedFile(file, name, type, System.currentTimeMillis())
            }
            return SharedPayload(input.text, input.title, files, directory, store)
        } catch (error: Throwable) { store.release(directory); throw error }
    }
}
