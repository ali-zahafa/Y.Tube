package com.tensei.freetube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLDecoder

class TikTokGuestTest {
    @Test fun supportsArabicAndEnglishSearch() {
        for (query in listOf("التجمع الشعبي لتصحيح المسار", "Cooking & baking")) {
            val url = requireNotNull(TikTokGuest.destination(query))
            assertEquals("https://html.duckduckgo.com/html/", url.substringBefore('?'))
            assertEquals("$query tiktok", URLDecoder.decode(url.substringAfter("?q="), "UTF-8"))
            assertEquals(url, TikTokGuest.destination("$query tiktok"))
        }
    }

    @Test fun opensIndividualPostsInThePlayer() {
        val page = "https://www.tiktok.com/@chef/video/6718335390845095173"
        val player = "https://www.tiktok.com/player/v1/6718335390845095173?autoplay=1&controls=1"
        assertEquals(player, TikTokGuest.destination(page))
        assertEquals(player, TikTokGuest.navigation(page))
        assertEquals(player, TikTokGuest.navigation(player))
    }

    @Test fun routesSearchSuggestionsAndResults() {
        assertEquals(TikTokGuest.destination("طبخ"), TikTokGuest.navigation("https://www.tiktok.com/search?q=%D8%B7%D8%A8%D8%AE"))
        assertEquals("https://www.tiktok.com/@food", TikTokGuest.navigation("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.tiktok.com%2F%40food"))
        assertNull(TikTokGuest.navigation("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com"))
    }

    @Test fun opensHandlesAndRestrictsLinks() {
        assertEquals("https://www.tiktok.com/@chef", TikTokGuest.destination("@chef"))
        for (bad in listOf("", "http://tiktok.com", "https://tiktok.com.evil.test",
                "https://www.tiktok.com@evil.test/video/123", "https://www.tiktok.com:8443")) {
            assertNull(TikTokGuest.destination(bad))
        }
    }
}
