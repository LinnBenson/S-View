package io.todu.sview.share

import android.content.Intent
import android.net.Uri

/** URL 参数只解码一次；同名项目由调用方按列表顺序匹配首项。 */
internal data class ShareInput(val projectName: String?, val text: String, val title: String, val uris: List<Uri>) {
    val scheme: Boolean get() = projectName != null
    companion object {
        @Suppress("DEPRECATION")
        fun parse(intent: Intent, watch: Boolean): ShareInput {
            if (intent.action == Intent.ACTION_VIEW) {
                val uri = requireNotNull(intent.data) { "缺少启动链接" }
                require(uri.scheme == "sview" && uri.host == "task" && uri.path.orEmpty() in listOf("", "/")) { "不支持的启动链接" }
                val name = uri.getQueryParameter("name") ?: error("缺少项目名")
                require(name.isNotBlank() && name.length <= 1024) { "项目名无效" }
                val text = uri.getQueryParameter("share").orEmpty()
                require(text.length <= 262144) { "分享文本不能超过 256K 字符" }
                return ShareInput(name, text, "URL Schemes", emptyList())
            }
            require(!watch) { "手表不接收其他应用分享" }
            require(intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) { "不支持的分享方式" }
            val files = linkedSetOf<Uri>()
            if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(files::add)
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(files::addAll)
            val clip = intent.clipData
            if (clip != null) {
                require(clip.itemCount <= 16) { "一次最多分享 16 个文件" }
                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(files::add)
            }
            require(files.size <= 16) { "一次最多分享 16 个文件" }
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: if (files.isEmpty() && clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }.joinToString("\n") else ""
            val title = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString().orEmpty()
            require(text.length <= 262144 && title.length <= 4096) { "分享文字过长" }
            require(files.isNotEmpty() || text.isNotEmpty()) { "分享内容为空" }
            return ShareInput(null, text, title, files.toList())
        }
    }
}
