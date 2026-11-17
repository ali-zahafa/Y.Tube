package com.tensei.ytxtube

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Immutable per-video request context, shared by its manifest and segment loaders. */
internal class FallbackMediaContext(val userAgent: String, private val streamingPoToken: String?) {
    fun mediaUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val trusted = parsed.host == "googlevideo.com" || parsed.host.endsWith(".googlevideo.com") ||
            ((parsed.host == "youtube.com" || parsed.host.endsWith(".youtube.com")) && parsed.encodedPath.startsWith("/api/manifest/"))
        if (!trusted || parsed.scheme != "https" || streamingPoToken.isNullOrBlank()) return url
        return parsed.newBuilder().setQueryParameter("pot", streamingPoToken).build().toString()
    }
}
