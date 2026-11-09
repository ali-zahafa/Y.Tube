package com.tensei.ytxtube

import com.tensei.ytxtube.SearchFilters.DateFilter
import com.tensei.ytxtube.SearchFilters.TypeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SearchFiltersTest {

    @Test
    fun params_matchYouTubesOwnFilterValues() {
        // The exact values YouTube's website uses in "sp=" for these filters.
        assertEquals("EgIIAg==", SearchFilters(date = DateFilter.Day).params())
        assertEquals("EgIIAw==", SearchFilters(date = DateFilter.Week).params())
        assertEquals("EgIIBA==", SearchFilters(date = DateFilter.Month).params())
        assertEquals("EgIQAQ==", SearchFilters(type = TypeFilter.Videos).params())
        assertEquals("EgIQCQ==", SearchFilters(type = TypeFilter.Shorts).params())
        assertEquals("EgJAAQ==", SearchFilters(type = TypeFilter.Live).params())
        // Combined: this week + videos.
        assertEquals("EgQIAxAB", SearchFilters(DateFilter.Week, TypeFilter.Videos).params())
    }

    @Test
    fun noFilters_noParams_queryUnchanged() {
        val f = SearchFilters()
        assertNull(f.params())
        assertFalse(f.isActive)
        assertEquals("cats", f.query("  cats "))
    }

    @Test
    fun customRange_usesInclusiveAfterBeforeOperators() {
        val f = SearchFilters(DateFilter.Custom, TypeFilter.Shorts, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
        assertEquals("match after:2026-01-01 before:2026-02-01", f.query("match"))
        assertEquals("EgIQCQ==", f.params()) // date range is in the query, type in params
        assertTrue(f.isActive)
        assertEquals("2026-01-01 – 2026-01-31 · Shorts", f.summary())
    }

    @Test
    fun base64_handlesPadding() {
        assertEquals("", SearchFilters.base64(byteArrayOf()))
        assertEquals("Zg==", SearchFilters.base64("f".toByteArray()))
        assertEquals("Zm8=", SearchFilters.base64("fo".toByteArray()))
        assertEquals("Zm9v", SearchFilters.base64("foo".toByteArray()))
    }
}
