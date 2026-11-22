package io.todu.sview.data

/** 分享接收按实际是否包含文件分类，与发送方声明的 MIME 无关。 */
enum class ShareSource(val value: String, val label: String) {
    DISABLED("disabled", "禁用"), ALL("all", "全部"), TEXT("text", "文本"), FILE("file", "文件");
    fun accepts(files: Boolean): Boolean = this == ALL || this == if (files) FILE else TEXT
    companion object {
        fun parse(value: String): ShareSource = entries.firstOrNull { it.value == value } ?: DISABLED
    }
}
