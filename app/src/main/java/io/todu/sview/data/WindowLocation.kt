package io.todu.sview.data

/** 位置标识同时用于配置、备份和脚本接口。左右始终指屏幕的物理方向。 */
enum class WindowLocation(val value: String, val label: String) {
    CENTER("center", "完全居中"), TOP("top", "上居中"), BOTTOM("bottom", "下居中"),
    TOP_LEFT("topLeft", "左上角"), TOP_RIGHT("topRight", "右上角"),
    BOTTOM_LEFT("bottomLeft", "左下角"), BOTTOM_RIGHT("bottomRight", "右下角");

    companion object {
        fun parse(value: String): WindowLocation = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("无效的窗口位置：$value")
    }
}
