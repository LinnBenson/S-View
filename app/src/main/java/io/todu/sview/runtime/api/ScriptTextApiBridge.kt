package io.todu.sview.runtime.api

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** 在接口工作线程按需计算，不访问网络或保存临时图片。 */
internal class ScriptTextApiBridge {
    fun invoke(request: JSONObject): JSONObject = try {
        val method = request.getString("method")
        val value = request.getJSONObject("args").opt("text")
        require(value is String) { "text 应为字符串" }
        val result = when (method) {
            "script.md5" -> {
                require(value.length <= 8192) { "文字不能超过 8192 个字符" }
                md5(value)
            }
            "script.qrcode" -> {
                require(value.isNotEmpty() && value.length <= 2000 && value.toByteArray(Charsets.UTF_8).size <= 2000) {
                    "二维码内容不能为空，UTF-8 编码不能超过 2000 字节"
                }
                qrcode(value)
            }
            else -> error("接口不存在")
        }
        JSONObject().put("ok", true).put("value", result)
    } catch (error: Exception) {
        ApiErrors.failure(error)
    }

    /** 空字符串也参与计算，结果固定为 32 位小写十六进制。 */
    private fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        return buildString(32) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 255
                append(digits[value ushr 4]); append(digits[value and 15])
            }
        }
    }

    /** 保留四模块白边和整数像素模块，避免二维码缩放后产生模糊边缘。 */
    private fun qrcode(text: String): String {
        val matrix = try {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4
            ))
        } catch (_: WriterException) {
            throw IllegalArgumentException("内容无法编码为二维码")
        }
        // 向上取整模块像素数，达到最小尺寸即可，不再通过外层补白凑足 256 像素。
        // 长内容仍至少保留每模块两像素，避免密集二维码难以识别。
        val scale = maxOf(2, (256 + matrix.width - 1) / matrix.width)
        val size = matrix.width * scale
        val pixels = IntArray(size * size) { Color.WHITE }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (!matrix[x, y]) continue
            for (dy in 0 until scale) {
                val start = (y * scale + dy) * size + x * scale
                java.util.Arrays.fill(pixels, start, start + scale, Color.BLACK)
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
        try {
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "二维码图片生成失败" }
            return "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }
}
