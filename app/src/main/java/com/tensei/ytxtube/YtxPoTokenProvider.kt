package com.tensei.ytxtube

import android.util.Log
import com.tensei.ytxtube.potoken.PoTokenGenerator
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

class YtxPoTokenProvider(private val generator: PoTokenGenerator) : PoTokenProvider {
    companion object {
        private const val TAG = "YtxPoTokenProvider"
    }

    private fun resolvePoToken(videoId: String?): PoTokenResult? {
        if (videoId.isNullOrBlank()) return null
        return try {
            val visitorData = VisitorDataProvider.getOrFetch()
            if (visitorData.isNullOrBlank()) {
                Log.w(TAG, "No valid visitorData available; WEB PoToken was not requested")
                return generator.getWebClientPoToken(videoId, "")
            }

            val result = generator.getWebClientPoToken(videoId, visitorData) ?: return null
            PoTokenResult(
                visitorData,
                result.playerRequestPoToken,
                result.streamingDataPoToken,
            )
        } catch (error: Exception) {
            Log.w(TAG, "PoToken provider failed: ${error.javaClass.simpleName}")
            null
        }
    }

    override fun getWebClientPoToken(videoId: String?): PoTokenResult? = resolvePoToken(videoId)
}
