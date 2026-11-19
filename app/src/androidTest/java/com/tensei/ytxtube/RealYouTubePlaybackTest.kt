package com.tensei.ytxtube

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-network smoke test for the exact videos that previously failed at 0:00/00:59.
 * It validates the same PlayerFallback response classification and the Media3 source
 * types used by the direct fallback path. The APK is not considered release-ready
 * unless these videos advance past the first rendered position.
 */
@RunWith(AndroidJUnit4::class)
class RealYouTubePlaybackTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun playback_4kPOYZq73hQ() = knownFailingVideosStartAndAdvance("4kPOYZq73hQ")
    @Test fun playback_QiZZJkHcW8A() = knownFailingVideosStartAndAdvance("QiZZJkHcW8A")
    @Test fun playback_CMSplvQ7_0A() = knownFailingVideosStartAndAdvance("CMSplvQ7_0A")

    private fun knownFailingVideosStartAndAdvance(id: String) {
        val resolved = PlayerFallback.forceClientFallback(id)
        if (!resolved) {
            val reason = PlayerFallback.lastFailureReason(id) ?: "no failure reason recorded"
            throw AssertionError("PlayerFallback could not obtain a playable response for $id: $reason")
        }

        val direct = PlayerFallback.directStreams(id)
        assertNotNull("No direct streams cached for " + id, direct)
        val streams = requireNotNull(direct)

        val player = ExoPlayer.Builder(context).build()
        val ready = CountDownLatch(1)
        val playbackError = AtomicReference<Throwable?>(null)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) ready.countDown()
            }

            override fun onPlayerError(playbackException: androidx.media3.common.PlaybackException) {
                playbackError.set(playbackException)
                ready.countDown()
            }
        })

        try {
            val source = when {
                !streams.hlsUrl.isNullOrBlank() ->
                    HlsMediaSource.Factory(dataSourceFactory(streams.hlsUrl))
                        .createMediaSource(
                            MediaItem.Builder()
                                .setMediaId(id)
                                .setUri(streams.hlsUrl)
                                .build(),
                        )

                !streams.dashManifestUrl.isNullOrBlank() ->
                    DashMediaSource.Factory(dataSourceFactory(streams.dashManifestUrl))
                        .createMediaSource(
                            MediaItem.Builder()
                                .setMediaId(id)
                                .setUri(streams.dashManifestUrl)
                                .build(),
                        )

                !streams.muxedUrl.isNullOrBlank() ->
                    ProgressiveMediaSource.Factory(dataSourceFactory(streams.muxedUrl))
                        .createMediaSource(
                            MediaItem.Builder()
                                .setMediaId(id)
                                .setUri(streams.muxedUrl)
                                .build(),
                        )

                !streams.videoUrl.isNullOrBlank() && !streams.audioUrl.isNullOrBlank() ->
                    MergingMediaSource(
                        ProgressiveMediaSource.Factory(dataSourceFactory(streams.videoUrl))
                            .createMediaSource(MediaItem.fromUri(streams.videoUrl)),
                        ProgressiveMediaSource.Factory(dataSourceFactory(streams.audioUrl))
                            .createMediaSource(MediaItem.fromUri(streams.audioUrl)),
                    )

                else -> throw IllegalStateException("No supported direct source for " + id)
            }

            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true

            assertTrue(
                "Video " + id + " did not reach READY: " + playbackError.get()?.message,
                ready.await(25, TimeUnit.SECONDS),
            )
            assertTrue(
                "Video " + id + " reported playback error: " + playbackError.get()?.message,
                playbackError.get() == null,
            )

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (System.nanoTime() < deadline && player.currentPosition <= 1000L) {
                Thread.sleep(250)
            }
            assertTrue(
                "Video " + id + " did not advance beyond 1 second; position=" + player.currentPosition,
                player.currentPosition > 1000L,
            )
        } finally {
            player.release()
        }
    }

    private fun dataSourceFactory(url: String): OkHttpDataSource.Factory {
        val userAgent = Net.mediaUserAgent(url) ?: Net.USER_AGENT
        return OkHttpDataSource.Factory(Net.mediaClient)
            .setDefaultRequestProperties(mapOf("User-Agent" to userAgent))
    }
}
