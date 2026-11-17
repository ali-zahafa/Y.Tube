package com.tensei.ytxtube.potoken

import java.time.Instant

internal object PoTokenLifetime {
    fun expiresAt(now: Instant, lifetimeSeconds: Long): Instant {
        require(lifetimeSeconds > 0) { "Integrity token has no usable lifetime" }
        // A fixed ten-minute margin immediately expired any shorter-lived token.
        val margin = minOf(600L, lifetimeSeconds / 10)
        return now.plusSeconds(lifetimeSeconds - margin)
    }
}
