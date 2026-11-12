package com.tensei.ytxtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerFallbackTest {
    @Test
    fun okWithoutAPlayableStreamUsesFallback() {
        assertTrue(PlayerFallback.isBlocked("""{"playabilityStatus":{"status":"OK"}}"""))
        assertTrue(PlayerFallback.isBlocked(ok("""{"formats":[],"adaptiveFormats":[]}""")))
        assertTrue(PlayerFallback.isBlocked(ok("""{"serverAbrStreamingUrl":"https://example.test/sabr"}""")))
        assertTrue(PlayerFallback.isBlocked(ok("""{"hlsManifestUrl":null,"dashManifestUrl":" "}""")))
    }

    @Test
    fun okWithSupportedStreamsKeepsOriginalResponse() {
        assertFalse(PlayerFallback.isBlocked(ok("""{"formats":[{"itag":18}]}""")))
        assertFalse(PlayerFallback.isBlocked(ok("""{"adaptiveFormats":[{"itag":140}]}""")))
        assertFalse(PlayerFallback.isBlocked(ok("""{"hlsManifestUrl":"https://example.test/live.m3u8"}""")))
        assertFalse(PlayerFallback.isBlocked(ok("""{"dashManifestUrl":"https://example.test/live.mpd"}""")))
    }

    @Test
    fun wrappedShortsResponseUsesSameClassification() {
        assertTrue(PlayerFallback.isBlocked("""{"playerResponse":${ok("{}")}}"""))
        assertFalse(PlayerFallback.isBlocked("""{"playerResponse":${ok("""{"formats":[{"itag":18}]}""")}}"""))
    }

    @Test
    fun loginRequiredStillUsesFallback() {
        assertTrue(PlayerFallback.isBlocked("""{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""))
    }

    private fun ok(streamingData: String): String =
        """{"playabilityStatus":{"status":"OK"},"streamingData":$streamingData}"""
}
