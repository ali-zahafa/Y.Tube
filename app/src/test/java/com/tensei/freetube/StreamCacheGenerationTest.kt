package com.tensei.freetube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCacheGenerationTest {

    @Test
    fun unchangedGeneration_allowsPublishing() {
        val generations = StreamCacheGeneration()
        val token = generations.snapshot("video-1")
        var published = false

        val accepted = generations.publishIfCurrent("video-1", token) {
            published = true
        }

        assertTrue(accepted)
        assertTrue(published)
    }

    @Test
    fun invalidatingVideo_rejectsEarlierExtraction() {
        val generations = StreamCacheGeneration()
        val token = generations.snapshot("video-1")
        generations.invalidate("video-1")
        var published = false

        val accepted = generations.publishIfCurrent("video-1", token) {
            published = true
        }

        assertFalse(accepted)
        assertFalse(published)
    }

    @Test
    fun invalidatingAnotherVideo_doesNotRejectCurrentExtraction() {
        val generations = StreamCacheGeneration()
        val token = generations.snapshot("video-1")
        generations.invalidate("video-2")

        assertTrue(generations.publishIfCurrent("video-1", token) {})
    }

    @Test
    fun invalidatingAll_rejectsEveryEarlierExtraction() {
        val generations = StreamCacheGeneration()
        val first = generations.snapshot("video-1")
        val second = generations.snapshot("video-2")
        generations.invalidateAll()

        assertFalse(generations.publishIfCurrent("video-1", first) {})
        assertFalse(generations.publishIfCurrent("video-2", second) {})
    }

    @Test
    fun newGeneration_canPublishAfterInvalidation() {
        val generations = StreamCacheGeneration()
        val old = generations.snapshot("video-1")
        generations.invalidate("video-1")
        val current = generations.snapshot("video-1")

        assertFalse(generations.publishIfCurrent("video-1", old) {})
        assertTrue(generations.publishIfCurrent("video-1", current) {})
    }
}
