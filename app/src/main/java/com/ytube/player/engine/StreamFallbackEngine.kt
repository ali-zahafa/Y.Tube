package com.ytube.player.engine

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SponsorSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String
)

data class ClientProfile(
    val name: String,
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val osName: String
)

object StreamFallbackEngine {

    // Auto-Fallback Client Profiles (SmartTube & Tubular technique to bypass 40-60s cutoff)
    val CLIENT_PROFILES = listOf(
        ClientProfile(
            name = "ANDROID_VR",
            clientName = "ANDROID_VR",
            clientVersion = "1.56.21",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android"
        ),
        ClientProfile(
            name = "IOS",
            clientName = "IOS",
            clientVersion = "19.29.1",
            userAgent = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
            osName = "iOS"
        ),
        ClientProfile(
            name = "ANDROID_TESTSUITE",
            clientName = "ANDROID_TESTSUITE",
            clientVersion = "1.9",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 14) gzip",
            osName = "Android"
        )
    )

    @Volatile
    var activeProfileIndex: Int = 0
        private set

    fun getActiveProfile(): ClientProfile = CLIENT_PROFILES[activeProfileIndex]

    fun switchToNextProfile(): ClientProfile {
        activeProfileIndex = (activeProfileIndex + 1) % CLIENT_PROFILES.size
        return getActiveProfile()
    }

    /**
     * Appends cpn (Client Playback Nonce) and range parameters to prevent YouTube 40-60s throttling/cutoff.
     */
    fun enhanceStreamUri(originalUri: Uri): Uri {
        val builder = originalUri.buildUpon()
        if (originalUri.getQueryParameter("cpn") == null) {
            val nonce = (1..16).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[(0..63).random()] }.joinToString("")
            builder.appendQueryParameter("cpn", nonce)
        }
        return builder.build()
    }

    /**
     * Fetches SponsorBlock segments (Intro, Outro, Sponsor, SelfPromo) for automatic skipping & seekbar coloring.
     */
    suspend fun fetchSponsorSegments(
        videoId: String,
        categories: List<String> = listOf("sponsor", "intro", "outro", "selfpromo", "interaction")
    ): List<SponsorSegment> = withContext(Dispatchers.IO) {
        val segments = mutableListOf<SponsorSegment>()
        try {
            val catsJson = JSONArray(categories).toString()
            val urlStr = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=${Uri.encode(catsJson)}"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val segArr = obj.getJSONArray("segment")
                    val startMs = (segArr.getDouble(0) * 1000).toLong()
                    val endMs = (segArr.getDouble(1) * 1000).toLong()
                    val category = obj.getString("category")
                    segments.add(SponsorSegment(startMs, endMs, category))
                }
            }
        } catch (_: Exception) {
            // Fail gracefully if offline or no segments found
        }
        segments
    }

    /**
     * Fetches clean DeArrow title to replace clickbait titles if enabled.
     */
    suspend fun fetchDeArrowTitle(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("https://sponsor.ajay.app/api/branding?videoID=$videoId").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val titles = json.optJSONArray("titles")
                if (titles != null && titles.length() > 0) {
                    val topTitle = titles.getJSONObject(0)
                    if (topTitle.optInt("votes", 0) >= 0) {
                        return@withContext topTitle.getString("title")
                    }
                }
            }
        } catch (_: Exception) {}
        null
    }
}
