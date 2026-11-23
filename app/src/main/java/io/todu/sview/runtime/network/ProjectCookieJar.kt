package io.todu.sview.runtime.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 原生 Cookie 按项目保存，不与网页或其他项目共用；响应批量更新后原子落盘。 */
internal class ProjectCookieJar(private val file: File) : CookieJar {
    private var closed = false
    private val cookies = mutableListOf<Cookie>()

    init {
        if (file.isFile) runCatching {
            val array = JSONArray(file.readText())
            repeat(array.length()) {
                val value = array.getJSONObject(it)
                val builder = Cookie.Builder().name(value.getString("name")).value(value.getString("value"))
                    .path(value.getString("path")).expiresAt(value.getLong("expires"))
                if (value.getBoolean("hostOnly")) builder.hostOnlyDomain(value.getString("domain"))
                else builder.domain(value.getString("domain"))
                if (value.getBoolean("secure")) builder.secure()
                if (value.getBoolean("httpOnly")) builder.httpOnly()
                builder.build().takeIf { it.expiresAt > System.currentTimeMillis() }?.let(cookies::add)
            }
        }
    }

    @Synchronized fun close() { closed = true; cookies.clear() }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (closed) return emptyList()
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (closed || cookies.isEmpty()) return
        val now = System.currentTimeMillis()
        var changed = this.cookies.removeAll { it.expiresAt <= now }
        cookies.forEach { cookie ->
            val previous = this.cookies.indexOfFirst { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt <= now) {
                if (previous >= 0) { this.cookies.removeAt(previous); changed = true }
            } else if (previous < 0) {
                this.cookies.add(cookie); changed = true
            } else {
                // 保持原有更新顺序，同名不同路径 Cookie 的选择顺序不变。
                if (this.cookies[previous] != cookie || previous != this.cookies.lastIndex) changed = true
                this.cookies.removeAt(previous)
                this.cookies.add(cookie)
            }
        }
        if (!changed) return
        // 会话 Cookie 也随项目保留，直到过期、服务端删除或用户清理缓存。
        val data = JSONArray()
        this.cookies.forEach { cookie -> data.put(JSONObject().put("name", cookie.name).put("value", cookie.value)
            .put("domain", cookie.domain).put("path", cookie.path).put("expires", cookie.expiresAt)
            .put("hostOnly", cookie.hostOnly).put("secure", cookie.secure).put("httpOnly", cookie.httpOnly)) }
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(data.toString())
        check(temporary.renameTo(file)) { "无法保存 Cookie" }
    }
}
