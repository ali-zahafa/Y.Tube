package com.tensei.ytxtube.potoken

import android.util.Log
import android.webkit.CookieManager
import com.tensei.ytxtube.VisitorDataProvider
import com.tensei.ytxtube.YTXTubeApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class PoTokenStatus {
    NOT_REQUESTED,
    OK,
    TIMEOUT,
    WEBVIEW_ERROR,
    NO_VISITOR_DATA,
    INVALIDATED,
}

class PoTokenGenerator {
    private val tag = "PoTokenGenerator"

    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    @Volatile private var webViewBadImpl = false

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    @Volatile
    internal var lastStatus: PoTokenStatus = PoTokenStatus.NOT_REQUESTED
        private set

    /**
     * Warm the WebView with the same genuine visitorData that will be used by the player request.
     * Network work stays off the main thread; WebView work is marshalled to Main by PoTokenWebView.
     */
    fun initialize() {
        if (!webViewSupported || webViewBadImpl) return
        GlobalScope.launch(Dispatchers.IO) {
            val sessionId = VisitorDataProvider.getOrFetch() ?: return@launch
            runCatching {
                webPoTokenGenLock.withLock {
                    if (webPoTokenGenerator == null ||
                        webPoTokenGenerator!!.isExpired ||
                        webPoTokenSessionId != sessionId
                    ) {
                        val old = webPoTokenGenerator
                        webPoTokenGenerator = null
                        webPoTokenStreamingPot = null
                        webPoTokenSessionId = null
                        withContext(Dispatchers.Main) { old?.close() }

                        val created = PoTokenWebView.getNewPoTokenGenerator(YTXTubeApplication.appContext)
                        val streamingToken = created.generatePoToken(sessionId)
                        webPoTokenGenerator = created
                        webPoTokenSessionId = sessionId
                        webPoTokenStreamingPot = streamingToken
                    }
                }
            }.onFailure {
                lastStatus = PoTokenStatus.WEBVIEW_ERROR
                Log.w(tag, "PoToken pre-initialization failed: ${it.javaClass.simpleName}")
            }
        }
    }

    fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        if (sessionId.isBlank()) {
            lastStatus = PoTokenStatus.NO_VISITOR_DATA
            return null
        }
        if (!webViewSupported || webViewBadImpl) {
            lastStatus = PoTokenStatus.WEBVIEW_ERROR
            return null
        }

        return try {
            val result = runBlocking {
                withTimeout(POTOKEN_TIMEOUT_MS) {
                    generateForSession(videoId, sessionId, forceRecreate = false)
                }
            }
            lastStatus = PoTokenStatus.OK
            result
        } catch (_: TimeoutCancellationException) {
            lastStatus = PoTokenStatus.TIMEOUT
            GlobalScope.launch(Dispatchers.IO) { invalidate() }
            Log.w(tag, "PoToken generation timed out")
            null
        } catch (error: Exception) {
            lastStatus = PoTokenStatus.WEBVIEW_ERROR
            if (error is BadWebViewException) webViewBadImpl = true
            Log.w(tag, "PoToken generation failed: ${error.javaClass.simpleName}")
            null
        }
    }

    /**
     * Clears the generator and its visitor-bound streaming token. Call from a worker coroutine
     * after a rejected stream; the next request creates a fresh WebView session.
     */
    suspend fun invalidate() {
        val old = webPoTokenGenLock.withLock {
            val current = webPoTokenGenerator
            webPoTokenGenerator = null
            webPoTokenStreamingPot = null
            webPoTokenSessionId = null
            current
        }
        withContext(Dispatchers.Main) { old?.close() }
        webViewBadImpl = false
        lastStatus = PoTokenStatus.INVALIDATED
    }

    private suspend fun generateForSession(
        videoId: String,
        sessionId: String,
        forceRecreate: Boolean,
    ): PoTokenResult {
        val (generator, streamingToken, recreated) = webPoTokenGenLock.withLock {
            val shouldRecreate = forceRecreate ||
                webPoTokenGenerator == null ||
                webPoTokenGenerator!!.isExpired ||
                webPoTokenSessionId != sessionId

            if (shouldRecreate) {
                val old = webPoTokenGenerator
                webPoTokenGenerator = null
                webPoTokenStreamingPot = null
                webPoTokenSessionId = null
                withContext(Dispatchers.Main) { old?.close() }

                val created = PoTokenWebView.getNewPoTokenGenerator(YTXTubeApplication.appContext)
                val streaming = created.generatePoToken(sessionId)
                webPoTokenGenerator = created
                webPoTokenSessionId = sessionId
                webPoTokenStreamingPot = streaming
            }

            Triple(
                requireNotNull(webPoTokenGenerator),
                requireNotNull(webPoTokenStreamingPot),
                shouldRecreate,
            )
        }

        val playerToken = try {
            generator.generatePoToken(videoId)
        } catch (error: Throwable) {
            if (recreated) throw error
            Log.w(tag, "Player PoToken generation failed; recreating WebView once")
            return generateForSession(videoId, sessionId, forceRecreate = true)
        }

        // Never log token contents: both tokens are bearer-like session material.
        Log.d(tag, "PoToken generated for video and visitor session")
        return PoTokenResult(
            playerRequestPoToken = playerToken,
            streamingDataPoToken = streamingToken,
        )
    }

    private companion object {
        const val POTOKEN_TIMEOUT_MS = 12_000L
    }
}
