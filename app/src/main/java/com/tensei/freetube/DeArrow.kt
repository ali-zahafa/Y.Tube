package com.tensei.freetube

import android.util.LruCache
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object DeArrow {
    private const val TTL_MS = 60 * 60 * 1000L
    private data class Title(val value: String?, val expiresAt: Long)
    private val titles = LruCache<String, Title>(128)
    private val client by lazy {
        Net.client.newBuilder().callTimeout(1500, TimeUnit.MILLISECONDS).build()
    }

    fun title(videoId: String): String? {
        if (!SuperPlayerSettings.deArrowTitles) return null
        val now = android.os.SystemClock.elapsedRealtime()
        titles.get(videoId)?.takeIf { it.expiresAt > now }?.let { return it.value }
        val prefix = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray(Charsets.UTF_8))
            .take(2).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val request = Request.Builder().url("https://sponsor.ajay.app/api/branding/$prefix").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) return null
                val firstTitle = if (response.isSuccessful) {
                    JSONObject(response.body?.string().orEmpty())
                        .optJSONObject(videoId)?.optJSONArray("titles")?.optJSONObject(0)
                } else null
                val result = firstTitle?.takeIf {
                    !it.optBoolean("original") && (it.optBoolean("locked") || it.optInt("votes", -1) >= 0)
                }?.optString("title")?.trim()?.takeIf { it.isNotEmpty() }
                titles.put(videoId, Title(result, now + TTL_MS))
                result
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }
}
