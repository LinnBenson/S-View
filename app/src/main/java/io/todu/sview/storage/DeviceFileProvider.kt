package io.todu.sview.storage

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/** 链接打开只提供读取，外部应用不能借授权改写设备文件。 */
class DeviceFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        require(mode == "r") { "仅允许读取文件" }
        return super.openFile(uri, mode)
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("仅允许读取文件")
}
