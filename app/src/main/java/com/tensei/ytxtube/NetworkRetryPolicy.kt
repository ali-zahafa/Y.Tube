package com.tensei.ytxtube

import androidx.media3.common.PlaybackException

/**
 * Which playback errors are worth retrying automatically once the network is usable again,
 * and how long to wait between timed attempts. Pure logic so it can be unit tested.
 *
 * HTTP 403/410 style failures (expired stream URLs) are not handled here: those go through
 * StreamRecoverer, which re-extracts the stream instead of re-requesting a dead URL.
 */
object NetworkRetryPolicy {
    /** Back-off before each timed retry. A network callback can trigger a retry earlier. */
    val DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L)

    val maxTimedAttempts: Int get() = DELAYS_MS.size

    fun isRecoverable(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT -> true
        else -> false
    }

    /** Delay before timed attempt number [attempt] (0-based), or null once attempts are used up. */
    fun delayFor(attempt: Int): Long? = DELAYS_MS.getOrNull(attempt)
}
