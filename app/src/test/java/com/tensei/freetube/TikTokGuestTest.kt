package com.tensei.freetube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLDecoder

class TikTokGuestTest {
    @Test fun supportsArabicAndEnglishSearch() {
        for (query in listOf("تعليم الطبخ", "Cooking & baking")) {
            for (videos in listOf(true, false)) {
                val url = requireNotNull(TikTokGuest.destination(query, videos))
                val tab = if (videos) "video" else "user"
                assertEquals("https://www.tiktok.com/search/$tab", url.substringBefore('?'))
                assertEquals(query, URLDecoder.decode(url.substringAfter("?q="), "UTF-8"))
            }
        }
    }

    @Test fun opensHandlesAndRestrictsLinks() {
        assertEquals("https://www.tiktok.com/@chef", TikTokGuest.destination("@chef", false))
        for (bad in listOf("", "http://tiktok.com", "https://tiktok.com.evil.test",
                "https://www.tiktok.com@evil.test/video/123", "https://www.tiktok.com:8443")) {
            assertNull(TikTokGuest.destination(bad, false))
        }
    }
}
