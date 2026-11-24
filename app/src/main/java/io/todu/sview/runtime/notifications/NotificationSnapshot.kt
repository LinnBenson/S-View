package io.todu.sview.runtime.notifications

import android.app.Notification
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/** 只导出有界文本和标识信息，不传递图像、Intent 或第三方 Parcelable。 */
internal object NotificationSnapshot {
    fun read(value: StatusBarNotification, type: String): JSONObject {
        val notification = value.notification
        val extras = notification.extras
        fun text(key: String) = extras?.getCharSequence(key)?.toString()?.take(4096).orEmpty()
        val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().take(16)
        return JSONObject().put("type", type).put("key", value.key).put("packageName", value.packageName)
            .put("id", value.id).put("tag", value.tag ?: JSONObject.NULL).put("postTime", value.postTime)
            .put("title", text(Notification.EXTRA_TITLE)).put("text", text(Notification.EXTRA_TEXT))
            .put("bigText", text(Notification.EXTRA_BIG_TEXT)).put("subText", text(Notification.EXTRA_SUB_TEXT))
            .put("textLines", JSONArray(lines.map { it.toString().take(4096) }))
            .put("channelId", notification.channelId ?: JSONObject.NULL)
            .put("category", notification.category ?: JSONObject.NULL).put("groupKey", value.groupKey)
            .put("ongoing", value.isOngoing).put("clearable", value.isClearable)
    }
}
