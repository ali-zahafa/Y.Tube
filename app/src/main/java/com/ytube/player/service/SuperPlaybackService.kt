package com.ytube.player.service

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.ytube.player.engine.StreamFallbackEngine
import java.io.File

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private var loudnessEnhancer: LoudnessEnhancer? = null

    companion object {
        private var simpleCache: SimpleCache? = null
    }

    override fun onCreate() {
        super.onCreate()
        initDiskCache()
        player = buildSuperExoPlayer()
        setupPlayerListeners()

        val sessionActivityPendingIntent = packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
            PendingIntent.getActivity(this, 0, sessionIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {})
            .apply { sessionActivityPendingIntent?.let { setSessionActivity(it) } }
            .build()
    }

    private fun initDiskCache() {
        if (simpleCache == null) {
            val cacheDir = File(cacheDir, "ytube_smart_cache")
            val evictor = LeastRecentlyUsedCacheEvictor(512L * 1024L * 1024L) // 512 MB Smart Cache
            simpleCache = SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(this))
        }
    }

    private fun buildSuperExoPlayer(): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(10_000)
            .setAllowCrossProtocolRedirects(true)

        val resolvingFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val profile = StreamFallbackEngine.getActiveProfile()
            val enhancedUri = StreamFallbackEngine.enhanceStreamUri(dataSpec.uri)
            dataSpec.buildUpon()
                .setUri(enhancedUri)
                .setHttpRequestHeaders(
                    mapOf(
                        "User-Agent" to profile.userAgent,
                        "Origin" to "https://www.youtube.com",
                        "Referer" to "https://www.youtube.com/"
                    )
                )
                .build()
        }

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache!!)
            .setUpstreamDataSourceFactory(resolvingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_200, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    private fun setupPlayerListeners() {
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupAudioNormalization(audioSessionId, enabled = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                // Automatic Client Fallback if stream fails or cuts off at 40-60s
                val currentPos = player.currentPosition
                StreamFallbackEngine.switchToNextProfile()
                player.prepare()
                player.seekTo(currentPos)
                player.play()
            }
        })
    }

    /**
     * Toggles Silence Skipping (LibreTube / NewPipe feature for podcasts & lectures)
     */
    fun setSilenceSkippingEnabled(enabled: Boolean) {
        player.skipSilenceEnabled = enabled
    }

    /**
     * Audio Normalization (InnerTune / Metrolist LoudnessEnhancer)
     */
    fun setupAudioNormalization(audioSessionId: Int, enabled: Boolean, targetGainMb: Int = 300) {
        try {
            loudnessEnhancer?.release()
            if (enabled && audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                    setTargetGain(targetGainMb)
                    setEnabled(true)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Dynamic Track Switching: Disables video track decoding when app is in background or screen is off.
     */
    fun setBackgroundAudioOnlyMode(isInBackground: Boolean) {
        val params = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isInBackground)
            .build()
        player.trackSelectionParameters = params
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        loudnessEnhancer?.release()
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }
}
