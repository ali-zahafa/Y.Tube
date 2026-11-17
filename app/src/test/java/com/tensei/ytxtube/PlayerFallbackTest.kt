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
        assertFalse(PlayerFallback.isBlocked(ok("""{"formats":[{"itag":18,"url":"https://example.test/video"}]}""")))
        assertFalse(PlayerFallback.isBlocked(ok("""{"adaptiveFormats":[{"itag":140,"url":"https://example.test/audio"}]}""")))
        assertFalse(PlayerFallback.isBlocked(ok("""{"hlsManifestUrl":"https://example.test/live.m3u8"}""")))
        assertFalse(PlayerFallback.isBlocked(ok("""{"dashManifestUrl":"https://example.test/live.mpd"}""")))
    }

    @Test
    fun wrappedShortsResponseUsesSameClassification() {
        assertTrue(PlayerFallback.isBlocked("""{"playerResponse":${ok("{}")}}"""))
        assertFalse(PlayerFallback.isBlocked("""{"playerResponse":${ok("""{"formats":[{"itag":18,"url":"https://example.test/video"}]}""")}}"""))
    }

    @Test
    fun cipherOnlyFormatsAreNotPlayable() {
        assertTrue(PlayerFallback.isBlocked(ok("""{"formats":[{"itag":18,"signatureCipher":"s=abc"}]}""")))
        assertTrue(PlayerFallback.isBlocked(ok("""{"adaptiveFormats":[{"itag":140,"url":""}]}""")))
    }

    @Test
    fun loginRequiredStillUsesFallback() {
        assertTrue(PlayerFallback.isBlocked("""{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""))
    }

    @Test
    fun dashOnlyResponseReachesTheManifestPlayer() {
        val direct = PlayerFallback.parseDirectStreams(org.json.JSONObject("""{"dashManifestUrl":"https://example.test/archive.mpd"}"""))
        org.junit.Assert.assertEquals("https://example.test/archive.mpd", direct?.dashUrl)
        org.junit.Assert.assertNull(direct?.videoUrl)
        assertTrue(PlayerFallback.isBlocked(ok("""{"dashManifestUrl":"http://example.test/insecure.mpd"}""")))
        assertTrue(PlayerFallback.isBlocked(ok("""{"hlsManifestUrl":"javascript:bad"}""")))
    }

    @Test
    fun refusalKeepsTheClientAndActualPlayabilityStatus() {
        val denied = org.json.JSONObject("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm"}}""")
        org.junit.Assert.assertEquals("WEB_EMBEDDED_PLAYER:LOGIN_REQUIRED(Sign in to confirm)",
            PlayerFallback.rejectionReason("WEB_EMBEDDED_PLAYER", denied))
        org.junit.Assert.assertEquals("IOS:OK(NO_SUPPORTED_STREAMS)",
            PlayerFallback.rejectionReason("IOS", org.json.JSONObject(ok("{}"))))
    }

    private fun ok(streamingData: String): String =
        """{"playabilityStatus":{"status":"OK"},"streamingData":$streamingData}"""
}
