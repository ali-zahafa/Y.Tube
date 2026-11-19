package com.tensei.freetube

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object PlayerFallback {
    private const val TAG = "PlayerFallback"
    private const val CACHE_MS = 3 * 60 * 1000L

    data class SourceMetadata(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val visitorData: String?,
        val poTokenPresent: Boolean,
    )

    private class Cached(val at: Long, val answer: JSONObject, val source: SourceMetadata)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val forcedClients = ConcurrentHashMap.newKeySet<String>()
    private val sourceMetadata = ConcurrentHashMap<String, SourceMetadata>()
    private val failureReasons = ConcurrentHashMap<String, String>()
    private val cacheGenerations = StreamCacheGeneration()

    fun lastFailureReason(videoId: String): String? = failureReasons[videoId]

    data class DirectStreams(
        val videoUrl: String?,
        val audioUrl: String?,
        val muxedUrl: String?,
        val hlsUrl: String?,
        val dashManifestUrl: String?,
    )

    /** Playable URLs from the Innertube response; never require a second NewPipe extraction. */
    fun directStreams(videoId: String): DirectStreams? {
        val saved = cache[videoId]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }
            ?: return null
        val streaming = saved.answer.optJSONObject("streamingData") ?: return null
        fun urls(key: String): List<JSONObject> {
            val array = streaming.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull(array::optJSONObject)
                .filter { it.optString("url").startsWith("https://") }
        }
        fun bitrate(item: JSONObject) = item.optLong("bitrate", 0L)
        val muxed = urls("formats").maxByOrNull(::bitrate)?.optString("url")
        val adaptive = urls("adaptiveFormats")
        val videos = adaptive.filter { it.optString("mimeType").startsWith("video/") }
        val audios = adaptive.filter { it.optString("mimeType").startsWith("audio/") }
        val video = videos.maxByOrNull(::bitrate)?.optString("url")
        val audio = audios.maxByOrNull(::bitrate)?.optString("url")
        val hls = streaming.optString("hlsManifestUrl").takeIf { it.startsWith("https://") }
        val dash = streaming.optString("dashManifestUrl").takeIf { it.startsWith("https://") }
        if (muxed == null && (video == null || audio == null) && hls == null && dash == null) return null

        listOfNotNull(muxed, video, audio, hls, dash).forEach {
            Net.registerMediaUserAgent(it, saved.source.userAgent)
        }
        return DirectStreams(video, audio, muxed, hls, dash)
    }

    fun metadata(videoId: String): SourceMetadata? = sourceMetadata[videoId]

    fun currentVisitorData(): String? =
        sourceMetadata.values.firstOrNull { !it.visitorData.isNullOrBlank() }?.visitorData

    fun invalidate(videoId: String) {
        cacheGenerations.invalidate(videoId)
        cache.remove(videoId); forcedClients.remove(videoId); sourceMetadata.remove(videoId); failureReasons.remove(videoId)
    }

    fun invalidateAll() {
        cacheGenerations.invalidateAll()
        cache.clear(); forcedClients.clear(); sourceMetadata.clear(); failureReasons.clear()
    }

    fun forceClientFallback(videoId: String, clientIndex: Int = 0): Boolean {
        val token = cacheGenerations.snapshot(videoId)
        val clients = fallbackClients()
        val failures = ArrayList<String>()
        for (index in clientIndex.coerceIn(0, clients.lastIndex)..clients.lastIndex) {
            val cfg = clients[index]
            val result = attempt(videoId, cfg, failures) ?: continue
            val (answer, meta) = result
            if (!isPlayable(answer)) continue
            val published = cacheGenerations.publishIfCurrent(videoId, token) {
                cache[videoId] = Cached(System.currentTimeMillis(), answer, meta)
                sourceMetadata[videoId] = meta
                forcedClients.add(videoId)
                failureReasons.remove(videoId)
            }
            if (published) return true
            return false
        }
        cacheGenerations.publishIfCurrent(videoId, token) {
            failureReasons[videoId] = failures.joinToString(" | ")
        }
        return false
    }

    fun handles(url: String) = "/youtubei/v1/reel/reel_item_watch" in url ||
        "/youtubei/v1/player" in url ||
        "/watch" in url

    /** The rescued player response, used for caption tracks when the extractor returns none. */
    internal fun playerResponse(videoId: String): JSONObject? {
        val cached = cache[videoId]
        if (cached != null && System.currentTimeMillis() - cached.at < CACHE_MS) return cached.answer
        return fetchFallback(videoId)
    }

    fun substitute(url: String, requestBody: ByteArray?, responseText: String?): String? {
        if (responseText == null) return null
        val videoId = videoIdFrom(requestBody) ?: videoIdFromUrl(url) ?: return responseText
        val cached = cache[videoId]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }
        if (videoId !in forcedClients) return OriginalPlayerResponse.substitute(url, requestBody, responseText)
        val answer = cached?.answer ?: fetchFallback(videoId) ?: return responseText
        return when {
            "/reel" in url -> JSONObject().put("playerResponse", answer).toString()
            "/watch" in url -> {
                if ("ytInitialPlayerResponse" in responseText) {
                    responseText.replace(
                        Regex("""(var\s+ytInitialPlayerResponse\s*=\s*|\bwindow\["ytInitialPlayerResponse"]\s*=\s*|\bytInitialPlayerResponse\s*=\s*)(\{.+?\});""", RegexOption.DOT_MATCHES_ALL),
                    ) { "var ytInitialPlayerResponse = $answer;" }
                } else {
                    "<!DOCTYPE html><html><head><script>var ytInitialPlayerResponse = $answer;</script></head><body></body></html>"
                }
            }
            else -> answer.toString()
        }
    }

    private fun fetchFallback(videoId: String): JSONObject? {
        val token = cacheGenerations.snapshot(videoId)
        val failures = ArrayList<String>()
        for (cfg in fallbackClients()) {
            val result = attempt(videoId, cfg, failures) ?: continue
            val (answer, meta) = result
            if (!isPlayable(answer)) continue
            val published = cacheGenerations.publishIfCurrent(videoId, token) {
                cache[videoId] = Cached(System.currentTimeMillis(), answer, meta)
                sourceMetadata[videoId] = meta
                failureReasons.remove(videoId)
            }
            if (published) return answer
            return null
        }
        cacheGenerations.publishIfCurrent(videoId, token) {
            failureReasons[videoId] = failures.joinToString(" | ")
        }
        return null
    }

    private fun attempt(
        videoId: String,
        cfg: ClientConfig,
        failures: MutableList<String>,
    ): Pair<JSONObject, SourceMetadata>? {
        var failure: String? = null
        val result = runCatching { askWithMeta(videoId, cfg) { failure = it } }
            .onFailure { if (failure == null) failure = "EXCEPTION(${it.javaClass.simpleName})" }
            .getOrNull()
        failure?.let {
            val diagnostic = "${cfg.name}:$it"
            failures.add(diagnostic)
            Log.w(TAG, diagnostic)
        }
        return result
    }

    private fun fallbackClients(): List<ClientConfig> = listOf(
        ClientConfig("ANDROID_VR", "28", "1.65.10",
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            "Android", "12L", "Oculus", "Quest 3", "MOBILE"),
        ClientConfig("TVHTML5", "7", "7.20260213.00.00",
            "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
            "Tizen", "4.0.0.2", "Samsung", "SmartTV", "TV"),
        ClientConfig("IOS", "5", "21.10.3",
            "com.google.ios.youtube/21.10.3 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            "iPhone", "18.1.0.22B83", "Apple", "iPhone16,2", "MOBILE"),
        ClientConfig("WEB_EMBEDDED_PLAYER", "56", "1.20260213.00.00",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Windows", "10.0", "", "", "DESKTOP"),
    )

    private fun videoIdFrom(requestBody: ByteArray?): String? {
        if (requestBody == null) return null
        val root = runCatching { JSONObject(String(requestBody)) }.getOrNull() ?: return null
        val player = root.optJSONObject("playerRequest") ?: root
        return player.optString("videoId").takeIf { it.length >= 11 }
    }

    private fun videoIdFromUrl(url: String): String? {
        Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("""/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)?.let { return it }
        val raw = url.substringAfterLast('/', "").substringBefore("?").substringBefore("&").trim()
        return raw.takeIf { it.length == 11 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
    }

    private data class ClientConfig(
        val name: String,
        val id: String,
        val version: String,
        val userAgent: String,
        val osName: String,
        val osVersion: String,
        val deviceMake: String,
        val deviceModel: String,
        val platform: String
    )

    private fun isPlayable(player: JSONObject): Boolean {
        val status = player.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        return status.equals("OK", ignoreCase = true) && hasPlayableStreams(player)
    }

    private fun hasPlayableStreams(player: JSONObject): Boolean {
        val streaming = player.optJSONObject("streamingData") ?: return false
        if (streaming.optString("hlsManifestUrl").startsWith("https://")) return true
        if (streaming.optString("dashManifestUrl").startsWith("https://")) return true

        // Do not treat cipher-only formats as directly playable in directStreams unless resolved;
        // verify actual HTTPS URLs exist in formats or adaptiveFormats.
        fun hasDirectUrl(arr: JSONArray?): Boolean {
            if (arr == null) return false
            for (i in 0 until arr.length()) {
                val url = arr.optJSONObject(i)?.optString("url").orEmpty()
                if (url.startsWith("https://")) return true
            }
            return false
        }
        return hasDirectUrl(streaming.optJSONArray("formats")) ||
            hasDirectUrl(streaming.optJSONArray("adaptiveFormats"))
    }

    internal fun isBlocked(body: String): Boolean {
        val root = runCatching { JSONObject(body) }.getOrNull()
        if (root != null) {
            val player = root.optJSONObject("playerResponse") ?: root
            val status = player.optJSONObject("playabilityStatus")
            val code = status?.optString("status").orEmpty()
            val reason = status?.optString("reason").orEmpty().lowercase()
            if (code.equals("OK", ignoreCase = true)) return !hasPlayableStreams(player)
            if (code.equals("LOGIN_REQUIRED", true) ||
                code.equals("UNPLAYABLE", true) ||
                code.equals("ERROR", true) ||
                code.equals("AGE_VERIFICATION_REQUIRED", true)
            ) return true
            if ("a bot" in reason || "not a bot" in reason || "confirm you" in reason) return true

            return player.optJSONObject("streamingData") == null
        }
        val lower = body.lowercase()
        return ("login_required" in lower || "unplayable" in lower) &&
            ("a bot" in lower || "not a bot" in lower || "confirm you" in lower || "error" in lower)
    }

    internal fun playabilityFailure(status: String, reason: String): String {
        if (status.equals("OK", ignoreCase = true)) return "NO_PLAYABLE_STREAMS"
        val code = when (status.uppercase()) {
            "LOGIN_REQUIRED", "UNPLAYABLE", "ERROR", "AGE_VERIFICATION_REQUIRED",
            "CONTENT_CHECK_REQUIRED", "LIVE_STREAM_OFFLINE" -> status.uppercase()
            else -> "UNKNOWN_STATUS"
        }
        // External error text can include URLs or credentials. Emit only known reason labels.
        val safeReason = when {
            reason.contains("not a bot", ignoreCase = true) -> "BOT_CHECK (Sign in to confirm you’re not a bot)"
            reason.contains("age", ignoreCase = true) && code == "AGE_VERIFICATION_REQUIRED" -> "AGE_CHECK"
            reason.contains("private video", ignoreCase = true) -> "PRIVATE_VIDEO"
            reason.contains("video unavailable", ignoreCase = true) -> "VIDEO_UNAVAILABLE"
            reason.isNotBlank() -> "UNRECOGNIZED_REASON"
            else -> null
        }
        return if (safeReason == null) code else "$code ($safeReason)"
    }

    private fun askWithMeta(
        videoId: String,
        cfg: ClientConfig,
        recordFailure: (String) -> Unit,
    ): Pair<JSONObject, SourceMetadata> {
        val client = JSONObject()
            .put("clientName", cfg.name)
            .put("clientVersion", cfg.version)
            .put("clientScreen", if (cfg.name == "WEB_EMBEDDED_PLAYER") "EMBED" else "WATCH")
            .put("platform", cfg.platform)
            .put("deviceMake", cfg.deviceMake)
            .put("deviceModel", cfg.deviceModel)
            .put("osName", cfg.osName)
            .put("osVersion", cfg.osVersion)
            .put("hl", "en")
            .put("gl", "US")

        val visitorData = VisitorDataProvider.getOrFetch()?.takeIf { it.isNotBlank() }
        visitorData?.let { client.put("visitorData", it) }

        var missingToken = false
        val poToken = if (cfg.name == "WEB_EMBEDDED_PLAYER") {
            if (visitorData != null) {
                val tokenResult = FreeTubeApplication.poTokenGenerator.getWebClientPoToken(videoId, visitorData)
                if (tokenResult?.playerRequestPoToken.isNullOrBlank()) missingToken = true
                tokenResult?.playerRequestPoToken
            } else {
                missingToken = true
                null
            }
        } else null

        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .apply {
                if (cfg.name == "WEB_EMBEDDED_PLAYER") {
                    put("thirdPartyEmbedUrl", "https://www.youtube.com/embed/$videoId")
                    if (!poToken.isNullOrBlank()) {
                        put("serviceIntegrityDimensions", JSONObject().put("poToken", poToken))
                    }
                }
            }

        val request = okhttp3.Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", cfg.userAgent)
            .header("X-Goog-Api-Format-Version", "2")
            .apply { visitorData?.let { header("X-Goog-Visitor-Id", it) } }
            .header("X-YouTube-Client-Name", cfg.id)
            .header("X-YouTube-Client-Version", cfg.version)
            .apply {
                val cookies = Account.cookieHeader()
                if (cookies.isNotBlank()) header("Cookie", cookies)
                if (cfg.name == "WEB_EMBEDDED_PLAYER") {
                    header("Origin", "https://www.youtube.com")
                    header("Referer", "https://www.youtube.com/embed/$videoId")
                }
            }
            .build()

        val tokenPrefix = if (missingToken) "[NO_TOKEN] " else ""
        return Net.client.newCall(request).execute().use { response ->
            val http = "HTTP_${response.code}"
            if (!response.isSuccessful) {
                recordFailure("$tokenPrefix$http")
                error("HTTP ${response.code}")
            }
            val answer = JSONObject(response.body!!.string())
            if (!isPlayable(answer)) {
                val status = answer.optJSONObject("playabilityStatus")
                val failure = playabilityFailure(status?.optString("status").orEmpty(), status?.optString("reason").orEmpty())
                recordFailure("$tokenPrefix$http $failure")
            }
            val meta = SourceMetadata(cfg.name, cfg.version, cfg.userAgent, visitorData, !poToken.isNullOrBlank())
            answer to meta
        }
    }
}
