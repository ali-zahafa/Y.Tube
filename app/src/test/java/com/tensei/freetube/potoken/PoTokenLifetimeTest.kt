package com.tensei.freetube.potoken

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PoTokenLifetimeTest {
    private val now = Instant.parse("2026-11-17T00:00:00Z")
    @Test fun shortLivedTokensAreNotExpiredImmediately() {
        for (seconds in listOf(1L, 60L, 300L, 600L)) {
            val expiry = PoTokenLifetime.expiresAt(now, seconds)
            assertTrue(expiry.isAfter(now))
            assertFalse(expiry.isAfter(now.plusSeconds(seconds)))
        }
    }
    @Test fun longLivedTokensRetainABoundedSafetyMargin() {
        assertEquals(now.plusSeconds(6600), PoTokenLifetime.expiresAt(now, 7200))
    }
    @Test(expected = IllegalArgumentException::class)
    fun unusableLifetimeIsRejected() { PoTokenLifetime.expiresAt(now, 0) }
}
