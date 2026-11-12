package com.tensei.ytxtube

internal object WatchHistoryPolicy {
    const val minimumPlaybackMs = 3_000L
    const val defaultRetentionDays = 7
    val retentionOptions = listOf(defaultRetentionDays, 14, 30)

    fun cutoff(nowMs: Long, retentionDays: Int): Long = nowMs - retentionDays * 86_400_000L

    fun resumePosition(positionMs: Long, durationMs: Long, live: Boolean): Long =
        if (live || (durationMs > 0 && positionMs >= durationMs - 2_000)) 0 else positionMs.coerceAtLeast(0)
}
