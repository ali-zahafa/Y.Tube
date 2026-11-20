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
                val scope = if (videos) "site:tiktok.com inurl:video" else "site:tiktok.com/@ -inurl:video"
                assertEquals("https://html.duckduckgo.com/html/", url.substringBefore('?'))
                assertEquals("$scope $query", URLDecoder.decode(url.substringAfter("?q="), "UTF-8"))
            }
        }
    }

    @Test fun routesSearchSuggestionsAndResults() {
        assertEquals(TikTokGuest.destination("طبخ", true), TikTokGuest.navigation("https://www.tiktok.com/search?q=%D8%B7%D8%A8%D8%AE"))
        assertEquals("https://www.tiktok.com/@food", TikTokGuest.navigation("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.tiktok.com%2F%40food"))
        assertNull(TikTokGuest.navigation("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com"))
    }

    @Test fun opensHandlesAndRestrictsLinks() {
        assertEquals("https://www.tiktok.com/@chef", TikTokGuest.destination("@chef", false))
        for (bad in listOf("", "http://tiktok.com", "https://tiktok.com.evil.test",
                "https://www.tiktok.com@evil.test/video/123", "https://www.tiktok.com:8443")) {
            assertNull(TikTokGuest.destination(bad, false))
        }
    }
}
