package com.tensei.ytxtube

/**
 * Guards asynchronous stream extraction results against cache invalidation.
 * A result may be published only if neither its video nor the whole cache
 * has been invalidated since extraction began.
 */
internal class StreamCacheGeneration {
    internal data class Token(val all: Long, val video: Long)

    private val lock = Any()
    private var allGeneration = 0L
    private val videoGenerations = HashMap<String, Long>()

    fun snapshot(videoId: String): Token = synchronized(lock) {
        Token(allGeneration, videoGenerations[videoId] ?: 0L)
    }

    fun invalidate(videoId: String) = synchronized(lock) {
        videoGenerations[videoId] = (videoGenerations[videoId] ?: 0L) + 1L
    }

    fun invalidateAll() = synchronized(lock) {
        allGeneration += 1L
        videoGenerations.clear()
    }

    fun publishIfCurrent(videoId: String, token: Token, publish: () -> Unit): Boolean =
        synchronized(lock) {
            if (token.all != allGeneration ||
                token.video != (videoGenerations[videoId] ?: 0L)
            ) {
                false
            } else {
                publish()
                true
            }
        }
}
