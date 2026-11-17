package com.tensei.ytxtube

import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class FallbackMediaContextTest {
    @Test fun manifestAndSegmentsUseTheSameStreamingTokenWithoutChangingSignatures() {
        val context = FallbackMediaContext("client-ua", "stream-token")
        for (url in listOf("https://manifest.googlevideo.com/api/manifest/dash/id/video?sig=signed",
            "https://rr1.googlevideo.com/videoplayback?sig=signed&pot=old")) {
            val updated = context.mediaUrl(url).toHttpUrl()
            assertEquals("stream-token", updated.queryParameter("pot"))
            assertEquals("signed", updated.queryParameter("sig"))
            assertEquals(1, updated.queryParameterValues("pot").size)
        }
    }
    @Test fun tokensStayWithinTheVideoContextAndTrustedHttpsMediaHosts() {
        val a = FallbackMediaContext("ua", "token-a")
        val b = FallbackMediaContext("ua", "token-b")
        val segment = "https://rr1.googlevideo.com/videoplayback"
        assertNotEquals(a.mediaUrl(segment), b.mediaUrl(segment))
        for (url in listOf("https://googlevideo.com.evil.test/video", "https://www.youtube.com/watch?v=id", "http://rr1.googlevideo.com/video")) {
            assertEquals(url, a.mediaUrl(url))
        }
    }
}
