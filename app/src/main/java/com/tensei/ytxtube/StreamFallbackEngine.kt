package com.tensei.ytxtube

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource

object StreamRecovery {
    var recovering by mutableStateOf<String?>(null)
        internal set

    var failed by mutableStateOf<Pair<String, String>?>(null)
        internal set

    internal enum class Route { Progressive, Muxed, Hls }

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
) : Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private var lastId: String? = null
    private var retryCount = 0
    private val routes = StreamRecovery.Route.entries

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId
        if (id == lastId) return
        lastId = id
        retryCount = 0
        id?.let(StreamRecovery::reset)
    }

    override fun onPlayerError(error: PlaybackException) {
        if (error.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return
        val item = player.currentMediaItem ?: return
        if (item.localConfiguration?.uri?.scheme == "file") return
        val id = item.mediaId
        val position = player.currentPosition
        if (id != lastId || StreamRecovery.routeFor(id) == null) {
            lastId = id
            retryCount = 0
        }
        if (retryCount >= 3) {
            Log.w("YTXTube", "stream refused after $retryCount attempts for $id at ${position / 1000}s; giving up")
            return
        }
        val route = routes[retryCount++]
        StreamRecovery.setRoute(id, route)
        Log.i("YTXTube", "stream refused for $id; trying $route at ${position / 1000}s")
        StreamRecovery.failed = null
        StreamRecovery.recovering = id
        StreamCache.invalidate(id)
        resolver.execute {
            val result = runCatching { StreamCache.getOrLoad(id) }
            main.post {
                StreamRecovery.recovering = null
                if (player.currentMediaItem?.mediaId != id || player.playerError == null) return@post
                result
                    .onSuccess { resume(item, position) }
                    .onFailure {
                        Log.w("YTXTube", "re-extracting $id failed", it)
                        StreamRecovery.failed = id to YouTube.friendlyError(it)
                    }
            }
        }
    }

    private fun resume(item: MediaItem, positionMs: Long) {
        val index = player.currentMediaItemIndex
        player.addMediaSource(index + 1, sources.createMediaSource(item))
        player.removeMediaItem(index)
        player.seekTo(index, positionMs)
        player.prepare()
    }
}
