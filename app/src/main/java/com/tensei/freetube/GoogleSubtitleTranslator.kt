package com.tensei.freetube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import java.util.LinkedHashMap
import kotlin.coroutines.coroutineContext

/**
 * Best-effort Google Translate web integration for caption text. No API key.
 * These are undocumented endpoints: requests may be throttled or rejected.
 * Video playback never depends on a translation response.
 */
internal object GoogleSubtitleTranslator {
    private const val TAG = "GoogleSubtitles"
    private const val MAX_BATCH_CHARS = 3000
    private const val MAX_BATCH_ITEMS = 40
    private val endpoints = listOf(
        "https://translate.googleapis.com/translate_a/t",
        "https://clients5.google.com/translate_a/t",
        "https://translate.google.com/translate_a/t",
    )

    private val cache = object : LinkedHashMap<String, String>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 3000
    }

    internal fun batches(texts: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var batch = mutableListOf<String>()
        var length = 0
        for (text in texts) {
            if (batch.isNotEmpty() && (batch.size >= MAX_BATCH_ITEMS || length + text.length > MAX_BATCH_CHARS)) {
                result += batch
                batch = mutableListOf()
                length = 0
            }
            batch += text
            length += text.length
        }
        if (batch.isNotEmpty()) result += batch
        return result
    }

    /** Each batch result is an array of strings or [translated, detected-language] entries. */
    internal fun parseBatch(body: String, expected: Int): List<String?> {
        val data = JSONArray(body)
        if (data.length() != expected) return List(expected) { null }
        return (0 until expected).map { index ->
            val value = data.opt(index)
            val text = if (value is JSONArray) value.optString(0) else value as? String
            text?.trim()?.takeIf(String::isNotBlank)
        }
    }

    private fun key(target: String, text: String): String = target + "\u0000" + text

    suspend fun translate(
        texts: List<String>,
        targetLanguage: String,
        onBatch: (Map<String, String>) -> Unit,
    ) {
        val target = targetLanguage.replace("zh-Hans", "zh-CN").replace("zh-Hant", "zh-TW")
        val unique = texts.filter(String::isNotBlank).distinct()
        val cached = synchronized(cache) {
            unique.mapNotNull { text -> cache[key(target, text)]?.let { text to it } }.toMap()
        }
        if (cached.isNotEmpty()) onBatch(cached)
        val missing = unique.filterNot(cached::containsKey)
        for (batch in batches(missing)) {
            coroutineContext.ensureActive()
            val translated = withContext(Dispatchers.IO) { requestBatch(batch, target) }
            val additions = batch.zip(translated).mapNotNull { (text, translation) ->
                translation?.let { text to it }
            }.toMap()
            if (additions.isNotEmpty()) {
                synchronized(cache) {
                    additions.forEach { (source, result) -> cache[key(target, source)] = result }
                }
                onBatch(additions)
            }
        }
    }

    private fun requestBatch(texts: List<String>, target: String): List<String?> {
        for (endpoint in endpoints) {
            try {
                val form = FormBody.Builder().apply { texts.forEach { add("q", it) } }.build()
                val url = okhttp3.HttpUrl.Builder()
                    .scheme("https")
                    .host(endpoint.substringAfter("https://").substringBefore('/'))
                    .addPathSegments("translate_a/t")
                    .addQueryParameter("client", if (endpoint.contains("clients5")) "dict-chrome-ex" else "gtx")
                    .addQueryParameter("sl", "auto")
                    .addQueryParameter("tl", target)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .post(form)
                    .build()
                Net.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Web translation HTTP " + response.code)
                        return@use
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.length > 1_000_000) return@use
                    val translations = parseBatch(body, texts.size)
                    if (translations.any { it != null }) return translations
                }
            } catch (error: Exception) {
                Log.w(TAG, "Google web translation endpoint unavailable", error)
            }
        }
        return List(texts.size) { null }
    }
}
