package com.tensei.ytxtube

/** A video id alone cannot distinguish A -> B -> A or a pause during extraction. */
internal class PlaybackRecoveryGeneration {
    private val generation = java.util.concurrent.atomic.AtomicLong()
    fun snapshot(): Long = generation.get()
    fun invalidate() { generation.incrementAndGet() }
    fun isCurrent(token: Long): Boolean = generation.get() == token
}
