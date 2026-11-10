package com.tensei.ytxtube

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRetryPolicyTest {

    @Test
    fun connectionErrors_areRecoverable() {
        assertTrue(NetworkRetryPolicy.isRecoverable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(NetworkRetryPolicy.isRecoverable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertTrue(NetworkRetryPolicy.isRecoverable(PlaybackException.ERROR_CODE_TIMEOUT))
    }

    @Test
    fun expiredUrlsAndDecoderErrors_areNotRetriedHere() {
        // 403s belong to StreamRecoverer (re-extraction); decoder/format errors cannot be fixed by retrying.
        assertFalse(NetworkRetryPolicy.isRecoverable(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(NetworkRetryPolicy.isRecoverable(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(NetworkRetryPolicy.isRecoverable(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertFalse(NetworkRetryPolicy.isRecoverable(PlaybackException.ERROR_CODE_DRM_UNSPECIFIED))
    }

    @Test
    fun backoff_growsAndIsBounded() {
        var previous = 0L
        for (i in 0 until NetworkRetryPolicy.maxTimedAttempts) {
            val delay = NetworkRetryPolicy.delayFor(i)!!
            assertTrue(delay > previous)
            previous = delay
        }
        assertNull(NetworkRetryPolicy.delayFor(NetworkRetryPolicy.maxTimedAttempts))
        assertEquals(4, NetworkRetryPolicy.maxTimedAttempts)
    }
}
