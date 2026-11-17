package com.tensei.ytxtube

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
    private class Cached(val at: Long, val answer: JSONObject, val source: SourceMetadata,
        val streamingPoToken: String? = null)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val forcedClients = ConcurrentHashMap.newKeySet<String>()
    private val sourceMetadata = ConcurrentHashMap<String, SourceMetadata>()
    private val cacheGenerations = StreamCacheGeneration()
    private val failureReasons = ConcurrentHashMap<String, String>()
    private val attemptedMetadata = ConcurrentHashMap<String, SourceMetadata>()

    fun lastFailureReason(videoId: String): String? = failureReasons[videoId]
    fun lastAttemptMetadata(videoId: String): SourceMetadata? = attemptedMetadata[videoId]
    internal fun mediaContext(videoId: String): FallbackMediaContext? = cache[videoId]?.let {
        FallbackMediaContext(it.source.userAgent, it.streamingPoToken)
    }


    data class DirectStreams(
        val videoUrl: String?,
        val audioUrl: String?,
        val muxedUrl: String?,
        val hlsUrl: String?,
        val dashUrl: String? = null,
    )

    /** Playable URLs from the Innertube response; never require a second NewPipe extraction. */
    fun directStreams(videoId: String): DirectStreams? {
        val saved = cache[videoId]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }
            ?: return null
        val direct = parseDirectStreams(saved.answer.optJSONObject("streamingData") ?: return null)
            ?: return null
        listOfNotNull(direct.muxedUrl, direct.videoUrl, direct.audioUrl, direct.hlsUrl, direct.dashUrl).forEach {
            Net.registerMediaUserAgent(it, saved.source.userAgent)
        }
        return direct
    }

    internal fun parseDirectStreams(streaming: JSONObject): DirectStreams? {
        fun urls(key: String): List<JSONObject> {
            val array = streaming.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull(array::optJSONObject)
                .filter { it.optString("url").startsWith("https://") }
        }
        fun bitrate(item: JSONObject) = item.optLong("bitrate", 0L)
        val muxed = urls("formats").maxByOrNull(::bitrate)?.optString("url")
        val adaptive = urls("adaptiveFormats")
        val video = adaptive.filter { it.optString("mimeType").startsWith("video/") }
            .maxByOrNull(::bitrate)?.optString("url")
        val audio = adaptive.filter { it.optString("mimeType").startsWith("audio/") }
            .maxByOrNull(::bitrate)?.optString("url")
        val hls = streaming.optString("hlsManifestUrl").takeIf { it.startsWith("https://") }
        val dash = streaming.optString("dashManifestUrl").takeIf { it.startsWith("https://") }
        if (muxed == null && video == null && hls == null && dash == null) return null
        return DirectStreams(video, audio, muxed, hls, dash)
    }

    fun metadata(videoId: String): SourceMetadata? = sourceMetadata[videoId]
    fun invalidate(videoId: String) {
        cacheGenerations.invalidate(videoId)
        cache.remove(videoId); forcedClients.remove(videoId); sourceMetadata.remove(videoId)
        attemptedMetadata.remove(videoId); failureReasons.remove(videoId)
    }
    fun invalidateAll() {
        cacheGenerations.invalidateAll()
        cache.clear(); forcedClients.clear(); sourceMetadata.clear()
        attemptedMetadata.clear(); failureReasons.clear()
    }

    fun forceClientFallback(videoId: String, clientIndex: Int = 0): Boolean {
        val token = cacheGenerations.snapshot(videoId)
        val clients = fallbackClients()
        for (index in clientIndex.coerceIn(0, clients.lastIndex)..clients.lastIndex) {
            val cfg = clients[index]
            val saved = ask(videoId, cfg, token) ?: continue
            val published = cacheGenerations.publishIfCurrent(videoId, token) {
                cache[videoId] = saved
                sourceMetadata[videoId] = saved.source
                forcedClients.add(videoId)
            }
            if (published) return true
            return false
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
        if (videoId !in forcedClients && !isBlocked(responseText)) return responseText
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
        for (cfg in fallbackClients()) {
            val saved = ask(videoId, cfg, token) ?: continue
            val published = cacheGenerations.publishIfCurrent(videoId, token) {
                cache[videoId] = saved
                sourceMetadata[videoId] = saved.source
            }
            if (published) return saved.answer
            return null
        }
        return null
    }

    private fun fallbackClients(): List<ClientConfig> = listOf(
        ClientConfig("WEB_EMBEDDED_PLAYER", "56", "2.20260708.00.00",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Windows", "10.0", "", "", "DESKTOP"),
        ClientConfig("ANDROID_VR", "28", "1.65.10",
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            "Android", "12L", "Oculus", "Quest 3", "MOBILE"),
        ClientConfig("TVHTML5", "7", "7.20260213.00.00",
            "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
            "Tizen", "4.0.0.2", "Samsung", "SmartTV", "TV"),
        ClientConfig("IOS", "5", "21.10.3",
            "com.google.ios.youtube/21.10.3 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            "iPhone", "18.1.0.22B83", "Apple", "iPhone16,2", "MOBILE"),
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
        if (listOf("hlsManifestUrl", "dashManifestUrl").any {
                streaming.optString(it).startsWith("https://")
            }) return true
        return listOf("formats", "adaptiveFormats").any { key ->
            val formats = streaming.optJSONArray(key) ?: return@any false
            (0 until formats.length()).any { index ->
                formats.optJSONObject(index)?.optString("url")?.startsWith("https://") == true
            }
        }
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

    private fun ask(videoId: String, cfg: ClientConfig, generation: StreamCacheGeneration.Token): Cached? {
        cacheGenerations.publishIfCurrent(videoId, generation) {
            attemptedMetadata[videoId] = SourceMetadata(cfg.name, cfg.version, cfg.userAgent, null, false)
        }
        return try {
            askResponse(videoId, cfg, generation)
        } catch (failure: Exception) {
            cacheGenerations.publishIfCurrent(videoId, generation) {
                failureReasons[videoId] = "${cfg.name}: ${failure.javaClass.simpleName}"
            }
            Log.w(TAG, "Fallback ${cfg.name} request failed: ${failure.javaClass.simpleName}")
            null
        }
    }

    private fun askResponse(videoId: String, cfg: ClientConfig, generation: StreamCacheGeneration.Token): Cached? {
        val client = JSONObject()
            .put("clientName", cfg.name)
            .put("clientVersion", cfg.version)
            .put("userAgent", cfg.userAgent)
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
        val poTokens = if (cfg.name == "WEB_EMBEDDED_PLAYER" && visitorData != null) {
            YTXTubeApplication.poTokenGenerator.getWebClientPoToken(videoId, visitorData)
        } else null
        val metadata = SourceMetadata(cfg.name, cfg.version, cfg.userAgent, visitorData,
            !poTokens?.playerRequestPoToken.isNullOrBlank())
        cacheGenerations.publishIfCurrent(videoId, generation) { attemptedMetadata[videoId] = metadata }
        val context = JSONObject().put("client", client)
        if (cfg.name == "WEB_EMBEDDED_PLAYER") {
            context.put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/"))
        }

        val body = JSONObject()
            .put("context", context)
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .apply {
                if (cfg.name == "WEB_EMBEDDED_PLAYER") {
                    poTokens?.playerRequestPoToken?.takeIf { it.isNotBlank() }?.let {
                        put("serviceIntegrityDimensions", JSONObject().put("poToken", it))
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

        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                cacheGenerations.publishIfCurrent(videoId, generation) {
                    failureReasons[videoId] = "${cfg.name}:HTTP_${response.code}"
                }
                return null
            }
            val answer = JSONObject(response.body?.string().orEmpty())
            if (!isPlayable(answer)) {
                val reason = rejectionReason(cfg.name, answer)
                cacheGenerations.publishIfCurrent(videoId, generation) { failureReasons[videoId] = reason }
                Log.w(TAG, "Fallback rejected: $reason")
                return null
            }
            cacheGenerations.publishIfCurrent(videoId, generation) { failureReasons.remove(videoId) }
            return Cached(System.currentTimeMillis(), answer, metadata, poTokens?.streamingDataPoToken)
        }
    }

    internal fun rejectionReason(client: String, answer: JSONObject): String {
        val playability = answer.optJSONObject("playabilityStatus")
        val status = playability?.optString("status")?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
        val reason = playability?.optString("reason").orEmpty().replace(Regex("[\\r\\n]+"), " ").take(300)
        val detail = reason.ifBlank { if (status == "OK") "NO_SUPPORTED_STREAMS" else "" }
        return "$client:$status${if (detail.isNotBlank()) "($detail)" else ""}"
    }

}
