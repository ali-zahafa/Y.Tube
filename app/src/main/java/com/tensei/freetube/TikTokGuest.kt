package com.tensei.freetube

import java.net.URI
import java.net.URLEncoder

internal object TikTokGuest {
    private val handle = Regex("@[a-zA-Z0-9_.]{1,24}")
    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443) &&
            (uri.host == "tiktok.com" || uri.host?.endsWith(".tiktok.com") == true)
    }.getOrDefault(false)

    fun destination(input: String, videos: Boolean): String? {
        val query = input.trim()
        if (query.isEmpty()) return null
        if (handle.matches(query)) return "https://www.tiktok.com/$query"
        if (query.contains("://")) return query.takeIf(::allowed)
        val type = if (videos) "video" else "user"
        return "https://www.tiktok.com/search/$type?q=" + URLEncoder.encode(query, "UTF-8")
    }
}
