package com.tensei.ytxtube

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryGenerationTest {
    @Test fun switchingAwayAndBackRejectsTheFirstVideoExtraction() {
        val guard = PlaybackRecoveryGeneration()
        val firstA = guard.snapshot()
        guard.invalidate() // A -> B
        val b = guard.snapshot()
        guard.invalidate() // B -> A
        assertFalse(guard.isCurrent(firstA))
        assertFalse(guard.isCurrent(b))
        assertTrue(guard.isCurrent(guard.snapshot()))
    }

    @Test fun pausingOrReleasingInvalidatesEveryPendingStage() {
        val guard = PlaybackRecoveryGeneration()
        val pending = List(6) { guard.snapshot() }
        guard.invalidate()
        pending.forEach { assertFalse(guard.isCurrent(it)) }
    }
}
