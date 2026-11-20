package com.tensei.freetube

import java.net.URI
import java.net.URLDecoder
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
        val scope = if (videos) "site:tiktok.com inurl:video" else "site:tiktok.com/@ -inurl:video"
        return "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode("$scope $query", "UTF-8")
    }

    fun indexPage(url: String): Boolean = runCatching {
        val u = URI(url)
        u.scheme == "https" && u.rawAuthority == "html.duckduckgo.com" && u.path in listOf("/html/", "/html")
    }.getOrDefault(false)

    fun navigation(url: String): String? = runCatching {
        val u = URI(url)
        fun parameter(key: String) = u.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")?.let { URLDecoder.decode(it, "UTF-8") }
        when {
            allowed(url) && (u.path == "/search" || u.path.startsWith("/search/")) ->
                parameter("q")?.takeIf { it.isNotBlank() }?.let { destination(it, u.path != "/search/user") } ?: url
            allowed(url) || indexPage(url) -> url
            u.scheme == "https" && u.rawAuthority in listOf("duckduckgo.com", "html.duckduckgo.com") && u.path == "/l/" ->
                parameter("uddg")?.takeIf(::allowed)
            else -> null
        }
    }.getOrNull()
}
