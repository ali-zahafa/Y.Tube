package com.tensei.ytxtube

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLinkAndCaptionTranslationTest {
    @Test
    fun copiedVideoLinksIgnoreShareParametersAndKeepShortsRouting() {
        assertEquals(YouTubeLinkTarget("abcdefghijk", false), parseClipboardVideo("https://youtu.be/abcdefghijk?si=abc"))
        assertEquals(YouTubeLinkTarget("abcdefghijk", true), parseClipboardVideo("https://www.youtube.com/shorts/abcdefghijk?feature=share"))
    }

    @Test
    fun clipboardDoesNotAcceptLookalikeHostsOrNonVideoText() {
        assertNull(parseClipboardVideo("https://youtube.com.example.org/watch?v=abcdefghijk"))
        assertNull(parseClipboardVideo("https://www.youtube.com/playlist?list=abcdefghijk"))
        assertNull(parseClipboardVideo("abcdefghijk"))
    }

    @Test
    fun translatedTrackKeepsSourceLanguageAndCredentials() {
        val source = "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=ar&sig=test&fmt=ttml&tlang=fr"
        val url = requireNotNull(translatedCaptionUrl(source, "en")).toHttpUrl()
        assertEquals("ar", url.queryParameter("lang"))
        assertEquals("test", url.queryParameter("sig"))
        assertEquals(listOf("en"), url.queryParameterValues("tlang"))
        assertEquals("vtt", url.queryParameter("fmt"))
        assertEquals("ar", requireNotNull(translatedCaptionUrl(source, "ar")).toHttpUrl().queryParameter("tlang"))
        assertNull(translatedCaptionUrl("https://example.org/api/timedtext", "ar"))
    }
}
