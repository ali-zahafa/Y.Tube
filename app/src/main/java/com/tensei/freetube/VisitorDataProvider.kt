package com.tensei.freetube

import android.content.Context
import okhttp3.Request

/**
 * Supplies a real YouTube visitorData value for guest sessions.
 *
 * The value is read from YouTube's own ytcfg bootstrap response and cached for six hours.
 * It is never synthesized locally: BotGuard tokens must be minted against the same visitorData
 * that is sent with the corresponding player request.
 */
internal object VisitorDataProvider {
    private const val PREFS = "youtube_visitor_session"
    private const val KEY_VALUE = "visitor_data"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private val lock = Any()

    @Volatile
    private var memoryValue: String? = null

    @Volatile
    private var memoryFetchedAt = 0L

    fun getOrFetch(forceRefresh: Boolean = false): String? {
        Account.visitorData()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!forceRefresh && memoryValue != null && now - memoryFetchedAt < TTL_MS) {
                return memoryValue
            }

            val prefs = FreeTubeApplication.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_VALUE, null)?.trim()
            val storedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
            if (!forceRefresh && !stored.isNullOrEmpty() && now - storedAt in 0 until TTL_MS) {
                memoryValue = stored
                memoryFetchedAt = storedAt
                return stored
            }

            return runCatching {
                val request = Request.Builder()
                    .url("https://www.youtube.com/?hl=en")
                    .header("User-Agent", Net.USER_AGENT)
                    .apply {
                        val cookies = Account.cookieHeader()
                        if (cookies.isNotBlank()) header("Cookie", cookies)
                    }
                    .build()

                Net.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val html = response.body?.string().orEmpty()
                    val value = extractVisitorData(html) ?: return@use null
                    val fetchedAt = System.currentTimeMillis()
                    memoryValue = value
                    memoryFetchedAt = fetchedAt
                    prefs.edit()
                        .putString(KEY_VALUE, value)
                        .putLong(KEY_FETCHED_AT, fetchedAt)
                        .apply()
                    value
                }
            }.getOrNull()
        }
    }

    fun invalidate() {
        synchronized(lock) {
            memoryValue = null
            memoryFetchedAt = 0L
            FreeTubeApplication.appContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_VALUE)
                .remove(KEY_FETCHED_AT)
                .apply()
        }
    }

    private fun extractVisitorData(html: String): String? {
        val patterns = listOf(
            Regex("""["']VISITOR_DATA["']\s*:\s*["']([^"']+)["']"""),
            Regex("""["']visitorData["']\s*:\s*["']([^"']+)["']"""),
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
