package io.todu.sview.runtime.api

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** 错误码用于脚本分支，中文说明用于展示；不靠匹配错误文案判断类型。 */
internal class ApiException(val code: String, message: String) : IllegalStateException(message)

internal object ApiErrors {
    fun cause(error: Throwable): Throwable = if (error is ExecutionException && error.cause != null) cause(error.cause!!) else error
    fun code(error: Throwable): String = when (val value = cause(error)) {
        is ApiException -> value.code
        is io.todu.sview.data.ProjectLogTarget.Failure -> value.code
        is SecurityException -> "PERMISSION_DENIED"
        is TimeoutException, is java.net.SocketTimeoutException -> "TIMEOUT"
        is java.util.concurrent.CancellationException, is InterruptedException -> "CANCELLED"
        is java.nio.file.NoSuchFileException, is java.io.FileNotFoundException -> "NOT_FOUND"
        is java.nio.file.FileAlreadyExistsException -> "ALREADY_EXISTS"
        is IllegalArgumentException, is org.json.JSONException -> "INVALID_ARGUMENT"
        is IOException -> "IO_ERROR"
        is IllegalStateException -> "INVALID_STATE"
        else -> "INTERNAL_ERROR"
    }
    fun failure(error: Throwable): JSONObject = JSONObject().put("ok", false)
        .put("error", cause(error).message ?: "操作失败").put("code", code(error))
}

/** 授权失败与一般设备状态分开返回，避免脚本解析提示文字。 */
internal fun checkPermission(allowed: Boolean, message: () -> String) {
    if (!allowed) throw SecurityException(message())
}
