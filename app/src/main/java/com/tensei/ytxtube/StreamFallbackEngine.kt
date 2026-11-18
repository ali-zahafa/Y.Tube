package com.tensei.ytxtube

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource

object StreamRecovery {
    // Compose-observable state is required here: PlayerState observes recovery completion
    // to switch to the website player when every direct-stream route has failed.
    var recovering: String? by androidx.compose.runtime.mutableStateOf(null)
        internal set
    var failed: Pair<String, String>? by androidx.compose.runtime.mutableStateOf(null)
        internal set

    internal enum class Route { DirectProgressive, Muxed, Hls, Dash }

    private val routes = android.util.LruCache<String, Route>(64)
    fun reset(videoId: String) { routes.remove(videoId) }
    internal fun routeFor(videoId: String): Route? = routes.get(videoId)
    internal fun setRoute(videoId: String, route: Route) { routes.put(videoId, route) }
}

@OptIn(UnstableApi::class)
internal class StreamRecoverer(
    private val player: ExoPlayer,
    private val sources: MediaSource.Factory,
    private val resolver: java.util.concurrent.Executor,
    private val cache: androidx.media3.datasource.cache.SimpleCache?,
) : Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val generation = PlaybackRecoveryGeneration()
    private var lastId: String? = null
    private var attempt = 0
    private var activeRecovery = false

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId
        if (id == lastId) return
        generation.invalidate()
        StreamRecovery.recovering = null
        lastId = id
        attempt = 0
        activeRecovery = false
        id?.let(StreamRecovery::reset)
        StreamRecovery.failed = null
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady && WebPlaybackSession.background) WebPlaybackSession.stop()
        if (!playWhenReady && activeRecovery) {
            generation.invalidate()
            activeRecovery = false
            StreamRecovery.recovering = null
        }
    }

    fun release() {
        generation.invalidate()
        activeRecovery = false
        StreamRecovery.recovering = null
        main.removeCallbacksAndMessages(null)
        player.removeListener(this)
    }

    override fun onPlayerError(error: PlaybackException) {
        val status = httpStatus(error) ?: -1
        if (status !in setOf(400, 403, 404, 410) &&
            error.errorCode != PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
            error.errorCode != PlaybackException.ERROR_CODE_IO_UNSPECIFIED) return
        val item = player.currentMediaItem ?: return
        if (item.localConfiguration?.uri?.scheme == "file") return
        val id = item.mediaId
        if (activeRecovery || attempt >= MAX_ATTEMPTS) return
        if (id != lastId) { lastId = id; attempt = 0 }

        val position = player.currentPosition.coerceAtLeast(0L)
        val shouldPlay = player.playWhenReady
        activeRecovery = true
        StreamRecovery.failed = null
        StreamRecovery.recovering = id
        runStage(item, id, position, shouldPlay, status, ++attempt, generation.snapshot())
    }

    private fun runStage(
        item: MediaItem,
        id: String,
        position: Long,
        shouldPlay: Boolean,
        status: Int,
        stage: Int,
        token: Long,
    ) {
        resolver.execute {
            if (!generation.isCurrent(token)) return@execute
            val result = runCatching {
                when (stage) {
                    1 -> {
                        evictFailedCache(id)
                        StreamCache.invalidate(id)
                        VisitorDataProvider.invalidate()
                        kotlinx.coroutines.runBlocking { YTXTubeApplication.poTokenGenerator.invalidate() }
                        StreamCache.getOrLoad(id)
                        null
                    }
                    2 -> {
                        StreamCache.invalidate(id)
                        StreamCache.getOrLoad(id)
                        StreamRecovery.Route.DirectProgressive
                    }
                    else -> {
                        evictFailedCache(id)
                        StreamCache.invalidate(id)
                        val clientIndex = stage - 3
                        check(PlayerFallback.forceClientFallback(id, clientIndex)) {
                            "No playable direct stream from fallback clients"
                        }
                        val direct = checkNotNull(PlayerFallback.directStreams(id)) {
                            "Fallback player response contained no supported direct URLs"
                        }
                        when {
                            direct.hlsUrl != null -> StreamRecovery.Route.Hls
                            direct.dashManifestUrl != null -> StreamRecovery.Route.Dash
                            direct.muxedUrl != null -> StreamRecovery.Route.Muxed
                            else -> StreamRecovery.Route.DirectProgressive
                        }
                    }
                }
            }
            main.post {
                // Ignore stale callbacks without clearing a newer video's recovery state.
                if (!generation.isCurrent(token) || player.currentMediaItem?.mediaId != id) return@post
                result.mapCatching { route ->
                    if (route == null) StreamRecovery.reset(id) else StreamRecovery.setRoute(id, route)
                    // MediaSource creation can also fail synchronously; keep it inside
                    // the same bounded recovery sequence instead of crashing main.
                    resume(item, position, shouldPlay)
                }.onFailure { failure ->
                    Log.w(TAG, "Recovery stage $stage failed for $id", failure)
                    if (stage < MAX_ATTEMPTS) {
                        attempt = stage + 1
                        runStage(item, id, position, shouldPlay, httpStatus(failure) ?: status, stage + 1, token)
                    } else {
                        activeRecovery = false
                        StreamRecovery.recovering = null
                        StreamRecovery.failed = id to diagnosticMessage(id, httpStatus(failure) ?: status, stage)
                    }
                }
            }
        }
    }

    private fun evictFailedCache(id: String) {
        StreamCache.get(id)?.let { info ->
            val picked = StreamSelection.pick(info)
            listOfNotNull(picked.video?.content, picked.muxed?.content, picked.audio?.content)
                .forEach { url -> runCatching { cache?.removeResource(url) } }
        }
    }

    private fun resume(item: MediaItem, positionMs: Long, playWhenReady: Boolean) {
        val index = player.currentMediaItemIndex
        if (index < 0) {
            activeRecovery = false
            StreamRecovery.recovering = null
            return
        }
        player.addMediaSource(index + 1, sources.createMediaSource(item))
        player.removeMediaItem(index)
        player.seekTo(index, positionMs)
        player.prepare()
        player.seekTo(positionMs)
        player.playWhenReady = playWhenReady
        activeRecovery = false
        StreamRecovery.recovering = null
    }

    private fun diagnosticMessage(id: String, status: Int, stage: Int): String {
        val meta = PlayerFallback.metadata(id)
        val route = StreamRecovery.routeFor(id)?.name ?: "Default"
        val tokenStatus = YTXTubeApplication.poTokenGenerator.lastStatus.name
        val reason = PlayerFallback.lastFailureReason(id)?.let { " | reason=$it" } ?: ""
        return "Playback could not recover. [client=${meta?.clientName ?: "NewPipe"} | route=$route | poToken=$tokenStatus | http=$status | stage=$stage$reason]"
    }

    private fun httpStatus(error: Throwable): Int? =
        generateSequence(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode

    private companion object {
        const val TAG = "YTXTubeRecovery"
        const val MAX_ATTEMPTS = 6
    }
}
