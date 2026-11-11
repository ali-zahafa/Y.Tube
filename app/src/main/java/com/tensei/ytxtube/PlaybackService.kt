package com.tensei.ytxtube

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.Stream
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

private const val ACTION_CLOSE = "com.tensei.ytxtube.CLOSE"
private const val ACTION_REWIND_10 = "com.tensei.ytxtube.REWIND_10"
private const val ACTION_FORWARD_10 = "com.tensei.ytxtube.FORWARD_10"
private const val ACTION_PREV = "com.tensei.ytxtube.PREV"
private const val ACTION_NEXT = "com.tensei.ytxtube.NEXT"

private val CLOSE_COMMAND = SessionCommand(ACTION_CLOSE, Bundle.EMPTY)
private val REWIND_10_COMMAND = SessionCommand(ACTION_REWIND_10, Bundle.EMPTY)
private val FORWARD_10_COMMAND = SessionCommand(ACTION_FORWARD_10, Bundle.EMPTY)
private val PREV_COMMAND = SessionCommand(ACTION_PREV, Bundle.EMPTY)
private val NEXT_COMMAND = SessionCommand(ACTION_NEXT, Bundle.EMPTY)

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val resolver = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))

    override fun onCreate() {
        super.onCreate()

        val sources = YouTubeMediaSourceFactory(this)
        val renderers = SyncedRenderersFactory(this).apply { setEnableDecoderFallback(true) }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(sources)
            .setBandwidthMeter(PlaybackBandwidth.get())
            .setTrackSelector(
                DefaultTrackSelector(
                    this,
                    AdaptiveTrackSelection.Factory(
                        2_000,
                        25_000,
                        25_000,
                        0.75f,
                    ),
                ).apply {
                    setParameters(
                        buildUponParameters()
                            .setAllowVideoMixedDecoderSupportAdaptiveness(true)
                            .setAllowVideoNonSeamlessAdaptiveness(true),
                    )
                },
            )
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setAllocator(androidx.media3.exoplayer.upstream.DefaultAllocator(true, 64 * 1024))
                    .setBufferDurationsMs(2_500, 30_000, 250, 2_000)
                    .setBackBuffer(10_000, false)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .setAudioAttributes(MediaAudio.attributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        player.setVideoFrameMetadataListener { _, releaseTimeNs, _, _ -> AvSync.onFrameReleased(releaseTimeNs) }
        sponsorSkipper = SponsorSkipper(player).also(player::addListener)
        player.addListener(SpeedKeeper(player))

        if (AudioOnly.enabled) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }

        player.addListener(StreamRecoverer(player, sources, resolver))
        networkRecoverer = NetworkRecoverer(this, player).also(player::addListener)
        resumeSaver = ResumeSaver(player).also(player::addListener)
        watchTime = WatchTimeReporter(player).also(player::addListener)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val player = session.player
        val keepWhilePaused = BackgroundPlaybackPolicy.keepNotification(
            hasMedia = player.mediaItemCount > 0,
            idle = player.playbackState == Player.STATE_IDLE,
            ended = player.playbackState == Player.STATE_ENDED,
        )
        super.onUpdateNotification(session, startInForegroundRequired || keepWhilePaused)
    }

    private fun closeFromNotification() {
        Log.i("YTXTube", "closed from the notification")
        resumeSaver?.saveNow()
        BackgroundAudioOverride.videoId = null
        session?.player?.run {
            stop()
            clearMediaItems()
        }
        AppExit.onExit?.invoke()
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        val keep = player != null && BackgroundPlaybackPolicy.keepServiceOnTaskRemoved(
            backgroundEnabled = BackgroundPlaybackPolicy.effectiveEnabled(
                BackgroundPlaybackSettings.enabled,
                BackgroundAudioOverride.videoId,
                player.currentMediaItem?.mediaId,
            ),
            playWhenReady = player.playWhenReady,
            hasMedia = player.mediaItemCount > 0,
            ended = player.playbackState == Player.STATE_ENDED,
        )
        if (keep) {
            Log.i("YTXTube", "task removed while playing; background playback keeps the service")
            return
        }
        pauseAllPlayersAndStopSelf()
    }

    private var resumeSaver: ResumeSaver? = null
    private var watchTime: WatchTimeReporter? = null
    private var networkRecoverer: NetworkRecoverer? = null
    private var sponsorSkipper: SponsorSkipper? = null

    override fun onDestroy() {
        resumeSaver?.saveNow()
        watchTime?.finish()
        watchTime?.release()
        networkRecoverer?.release()
        sponsorSkipper?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        resolver.shutdown()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(PREV_COMMAND)
                .add(REWIND_10_COMMAND)
                .add(FORWARD_10_COMMAND)
                .add(NEXT_COMMAND)
                .add(CLOSE_COMMAND)
                .build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()

            if (!session.isMediaNotificationController(controller)) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setAvailablePlayerCommands(playerCommands)
                    .build()
            }

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setMediaButtonPreferences(
                    ImmutableList.of(
                        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                            .setDisplayName("Previous")
                            .setSessionCommand(PREV_COMMAND)
                            .setSlots(CommandButton.SLOT_BACK)
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                            .setDisplayName("Rewind 10s")
                            .setSessionCommand(REWIND_10_COMMAND)
                            .setSlots(CommandButton.SLOT_OVERFLOW)
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                            .setDisplayName("Forward 10s")
                            .setSessionCommand(FORWARD_10_COMMAND)
                            .setSlots(CommandButton.SLOT_OVERFLOW)
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_NEXT)
                            .setDisplayName("Next")
                            .setSessionCommand(NEXT_COMMAND)
                            .setSlots(CommandButton.SLOT_FORWARD)
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                            .setDisplayName("Close")
                            .setCustomIconResId(R.drawable.ic_notification_close)
                            .setSessionCommand(CLOSE_COMMAND)
                            .setSlots(CommandButton.SLOT_OVERFLOW)
                            .build(),
                    ),
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val p = session.player
            when (customCommand.customAction) {
                ACTION_CLOSE -> {
                    closeFromNotification()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_REWIND_10 -> {
                    val target = (p.currentPosition - 10_000L).coerceAtLeast(0L)
                    p.seekTo(target)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_FORWARD_10 -> {
                    val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    val target = (p.currentPosition + 10_000L).coerceAtMost(dur)
                    p.seekTo(target)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_PREV -> {
                    NotificationQueueBridge.onSkipPrevious?.invoke() ?: run {
                        if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(0L)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_NEXT -> {
                    NotificationQueueBridge.onSkipNext?.invoke() ?: run {
                        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = resolver.submit<MutableList<MediaItem>> {
            mediaItems.map { item ->
                val local = item.requestMetadata.mediaUri?.takeIf { it.scheme == "file" }
                    ?: item.mediaMetadata.extras?.getString("local_uri")?.let(Uri::parse)
                if (local != null) {
                    item.buildUpon()
                        .setUri(local)
                        .setMediaMetadata(
                            item.mediaMetadata.buildUpon()
                                .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                                .build(),
                        )
                        .build()
                } else {
                    val info = StreamCache.getOrLoad(item.mediaId)
                    item.buildUpon()
                        .setUri(YouTube.watchUrl(item.mediaId))
                        .setMediaMetadata(
                            item.mediaMetadata.buildUpon()
                                .setTitle(item.mediaMetadata.title ?: info.name)
                                .setArtist(item.mediaMetadata.artist ?: info.uploaderName)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                                .build(),
                        )
                        .build()
                }
            }.toMutableList()
        }
    }
}

internal object NotificationQueueBridge {
    @Volatile var onSkipPrevious: (() -> Unit)? = null
    @Volatile var onSkipNext: (() -> Unit)? = null
}

internal object MediaAudio {
    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
}

object StreamRecovery {
    var recovering by mutableStateOf<String?>(null)
        internal set

    var failed by mutableStateOf<Pair<String, String>?>(null)
        internal set

    val forceProgressiveIds: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
}

@OptIn(UnstableApi::class)
private class StreamRecoverer(
    private val player: ExoPlayer,
    private val sources: MediaSource.Factory,
    private val resolver: java.util.concurrent.Executor,
) : Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private var lastId: String? = null
    private var retryCount = 0

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            retryCount = 0
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (error.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return
        val item = player.currentMediaItem ?: return
        if (item.localConfiguration?.uri?.scheme == "file") return
        val id = item.mediaId
        val position = player.currentPosition
        if (id != lastId) {
            lastId = id
            retryCount = 0
        }
        if (retryCount >= 3) {
            Log.w("YTXTube", "stream refused after $retryCount attempts for $id at ${position / 1000}s; giving up")
            return
        }
        retryCount++
        StreamRecovery.forceProgressiveIds.add(id)
        Log.i("YTXTube", "stream refused for $id (attempt $retryCount); re-extracting and resuming at ${position / 1000}s")
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

@OptIn(UnstableApi::class)
private class NetworkRecoverer(context: Context, private val player: ExoPlayer) : Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var failedId: String? = null
    private var attempts = 0
    private var callbackRegistered = false
    private var released = false
    private val timedRetry = Runnable { tryRecover(fromNetworkCallback = false) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                if (android.os.SystemClock.elapsedRealtime() - registeredAt < 1_000L) return@post
                tryRecover(fromNetworkCallback = true)
            }
        }
    }
    private var registeredAt = 0L

    override fun onPlayerError(error: PlaybackException) {
        if (released || !NetworkRetryPolicy.isRecoverable(error.errorCode)) return
        val item = player.currentMediaItem ?: return
        if (item.localConfiguration?.uri?.scheme == "file") return
        if (failedId != item.mediaId) {
            failedId = item.mediaId
            attempts = 0
        }
        registerCallback()
        val delay = NetworkRetryPolicy.delayFor(attempts)
        main.removeCallbacks(timedRetry)
        if (delay != null) {
            Log.i("YTXTube", "network error ${error.errorCodeName} for ${item.mediaId}; retrying in ${delay}ms")
            main.postDelayed(timedRetry, delay)
        } else {
            Log.w("YTXTube", "network error for ${item.mediaId}; waiting for a network change")
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) reset()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem?.mediaId != failedId) reset()
    }

    private fun tryRecover(fromNetworkCallback: Boolean) {
        if (released) return
        val error = player.playerError ?: return
        if (!NetworkRetryPolicy.isRecoverable(error.errorCode)) return
        if (player.currentMediaItem?.mediaId != failedId) return
        if (!fromNetworkCallback && !isOnline()) return
        if (!fromNetworkCallback) attempts++
        main.removeCallbacks(timedRetry)
        Log.i("YTXTube", "re-preparing $failedId after network error (${if (fromNetworkCallback) "network back" else "attempt $attempts"})")
        player.prepare()
    }

    private fun isOnline(): Boolean = runCatching {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    private fun registerCallback() {
        if (callbackRegistered || connectivity == null) return
        registeredAt = android.os.SystemClock.elapsedRealtime()
        callbackRegistered = runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }.isSuccess
    }

    private fun unregisterCallback() {
        if (!callbackRegistered) return
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        callbackRegistered = false
    }

    private fun reset() {
        failedId = null
        attempts = 0
        main.removeCallbacks(timedRetry)
        unregisterCallback()
    }

    fun release() {
        released = true
        reset()
    }
}

@OptIn(UnstableApi::class)
private class WatchTimeReporter(private val player: ExoPlayer) : Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var videoId: String? = null
    private var session: InnerTube.WatchSession? = null
    private var startedAt = 0L

    private val starts = ArrayList<Double>()
    private val ends = ArrayList<Double>()

    private var stretchFrom: Long? = null
    private var pings = 0
    private val tick = Runnable { onTick() }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId?.ifBlank { null }
        if (id == videoId) return
        finish()
        videoId = id
        if (id == null || !Account.signedIn) return
        startedAt = android.os.SystemClock.elapsedRealtime()
        if (player.isPlaying) startStretch()
        io.execute {
            val started = runCatching { InnerTube.startWatch(id) }
                .onFailure { Log.w("YTXTube", "watch start for $id", it) }.getOrNull()
            main.post { if (videoId == id) session = started }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (videoId == null || !Account.signedIn) return
        if (isPlaying) {
            startStretch()
        } else {
            endStretch(player.currentPosition)
            send(playing = false, final = false)
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (stretchFrom == null) return
        endStretch(oldPosition.positionMs)
        if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex && player.isPlaying) stretchFrom = newPosition.positionMs
    }

    fun finish() {
        main.removeCallbacks(tick)
        if (stretchFrom != null && player.currentMediaItem?.mediaId == videoId) endStretch(player.currentPosition)
        stretchFrom = null
        send(playing = false, final = true)
        videoId = null
        session = null
        starts.clear(); ends.clear()
        pings = 0
    }

    fun release() {
        main.removeCallbacks(tick)
        io.shutdown()
    }

    private fun startStretch() {
        stretchFrom = player.currentPosition
        schedule()
    }

    private fun endStretch(atMs: Long) {
        val from = stretchFrom ?: return
        stretchFrom = null
        main.removeCallbacks(tick)
        if (atMs > from + 250) { starts += from / 1000.0; ends += atMs / 1000.0 }
    }

    private fun schedule() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, PING_DELAYS_MS.getOrElse(pings) { PING_DELAYS_MS.last() })
    }

    private fun onTick() {
        if (!player.isPlaying) return
        val now = player.currentPosition
        endStretch(now)
        stretchFrom = now
        send(playing = true, final = false)
        schedule()
    }

    private fun send(playing: Boolean, final: Boolean) {
        val s = session ?: return
        if (starts.isEmpty() && !final) return
        pings++
        val position = player.currentPosition / 1000.0
        val elapsed = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val st = starts.toList()
        val et = ends.toList()
        starts.clear(); ends.clear()
        io.execute {
            runCatching { InnerTube.pingWatchtime(s, position, st, et, playing, elapsed, final) }
                .onFailure { Log.w("YTXTube", "watch time for ${s.videoId}", it) }
        }
    }

    private companion object {
        val PING_DELAYS_MS = longArrayOf(10_000, 10_000, 10_000, 40_000).toList()
    }
}

private class SpeedKeeper(private val player: ExoPlayer) : Player.Listener {
    private var videoId: String? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId?.ifBlank { null } ?: return
        if (id == videoId) return
        videoId = id
        player.setPlaybackSpeed(PlaybackSpeed.forNewVideo)
    }
}

@OptIn(UnstableApi::class)
private class SponsorSkipper(private val player: ExoPlayer) : Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        if (released) return
        io.execute {
            val segments = SponsorBlock.fetch(id)
            main.post {
                if (released) return@post
                SponsorBlock.forUi[id] = segments
                if (player.currentMediaItem?.mediaId != id) return@post
                val index = player.currentMediaItemIndex
                segments.forEach { segment ->
                    player.createMessage { _, _ -> skip(id, segment) }
                        .setLooper(Looper.getMainLooper())
                        .setPosition(index, segment.startMs)
                        .send()
                }
            }
        }
    }

    private var released = false

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }

    private fun skip(videoId: String, segment: SponsorBlock.Segment) {
        if (released) return
        if (!SponsorBlock.enabled || segment.action != SponsorBlock.Action.Auto) return
        if (player.currentMediaItem?.mediaId != videoId) return
        if (player.currentPosition > segment.endMs - 500) return
        Log.i("YTXTube", "SponsorBlock: skipping ${segment.category.key} ${segment.startMs}-${segment.endMs} in $videoId")
        ramp(from = 1f, to = 0f, durationMs = 300) {
            player.seekTo(segment.endMs)
            SponsorBlock.lastSkip = SponsorBlock.Skip(videoId, segment)
            ramp(from = 0f, to = 1f, durationMs = 450) {}
        }
    }

    private fun ramp(from: Float, to: Float, durationMs: Long, then: () -> Unit) {
        val start = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (released) return
                val t = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
                player.volume = from + (to - from) * t
                if (t < 1f) main.postDelayed(this, 16) else then()
            }
        }
        main.post(step)
    }
}

@OptIn(UnstableApi::class)
internal object YouTubeStreamHttp {
    private const val UA_IOS = "com.google.ios.youtube/21.10.3 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
    private const val UA_ANDROID_VR = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    private const val UA_ANDROID = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"
    private const val UA_TV = "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15"

    fun userAgentForUrl(url: String): String {
        val clientParam = Regex("[?&]c=([^&]+)").find(url)?.groupValues?.getOrNull(1)?.uppercase().orEmpty()
        return when {
            clientParam.contains("IOS") -> UA_IOS
            clientParam.contains("ANDROID_VR") -> UA_ANDROID_VR
            clientParam.contains("ANDROID") -> UA_ANDROID
            clientParam.contains("TVHTML5") -> UA_TV
            else -> Net.USER_AGENT
        }
    }

    fun createDataSourceFactory(context: Context): DataSource.Factory {
        val dynamicOkHttpFactory = object : DataSource.Factory {
            override fun createDataSource(): DataSource {
                val base = OkHttpDataSource.Factory(Net.client)
                    .setUserAgent(Net.USER_AGENT)
                    .createDataSource()
                return object : DataSource by base {
                    override fun open(dataSpec: DataSpec): Long {
                        val url = dataSpec.uri.toString()
                        val ua = userAgentForUrl(url)
                        val headers = HashMap(dataSpec.httpRequestHeaders)
                        headers["User-Agent"] = ua
                        if (ua == Net.USER_AGENT) {
                            headers.putIfAbsent("Origin", "https://www.youtube.com")
                            headers.putIfAbsent("Referer", "https://www.youtube.com/")
                        }
                        val updatedSpec = dataSpec.buildUpon()
                            .setHttpRequestHeaders(headers)
                            .build()
                        return base.open(updatedSpec)
                    }

                    override fun addTransferListener(transferListener: TransferListener) {
                        base.addTransferListener(transferListener)
                    }
                }
            }
        }
        return DefaultDataSource.Factory(context, dynamicOkHttpFactory)
    }
}

@OptIn(UnstableApi::class)
private class YouTubeMediaSourceFactory(context: Context) : MediaSource.Factory {
    private val dataSourceFactory: DataSource.Factory = YouTubeStreamHttp.createDataSourceFactory(context)
    private val fallback = DefaultMediaSourceFactory(dataSourceFactory)

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { fallback.setDrmSessionManagerProvider(provider) }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
        apply { fallback.setLoadErrorHandlingPolicy(policy) }

    override fun getSupportedTypes(): IntArray = fallback.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        buildSource(mediaItem)

    private fun buildSource(mediaItem: MediaItem): MediaSource {
        if (mediaItem.localConfiguration?.uri?.scheme == "file") {
            return fallback.createMediaSource(mediaItem)
        }
        val info = StreamCache.get(mediaItem.mediaId) ?: return fallback.createMediaSource(mediaItem)
        val isLive = info.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM
        val hlsUrl = info.hlsUrl?.ifBlank { null }
        val forceProgressive = mediaItem.mediaId in StreamRecovery.forceProgressiveIds

        if (isLive && !hlsUrl.isNullOrBlank()) {
            Log.i("YTXTube", "media source ${mediaItem.mediaId}: playing live HLS stream from $hlsUrl")
            return HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                MediaItem.Builder()
                    .setUri(hlsUrl)
                    .setMediaId(mediaItem.mediaId)
                    .setMediaMetadata(mediaItem.mediaMetadata)
                    .build(),
            )
        }
        val picked = StreamSelection.pick(info)

        if (picked.video == null && picked.muxed == null && !hlsUrl.isNullOrBlank()) {
            Log.i("YTXTube", "media source ${mediaItem.mediaId}: no progressive streams, falling back to HLS")
            return HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                MediaItem.Builder()
                    .setUri(hlsUrl)
                    .setMediaId(mediaItem.mediaId)
                    .setMediaMetadata(mediaItem.mediaMetadata)
                    .build(),
            )
        }

        if (!forceProgressive && QualityPreference.height == null && picked.audio != null) {
            val adaptive = adaptiveVideo(info, mediaItem)
            if (adaptive != null) {
                Log.i("YTXTube", "media source ${mediaItem.mediaId}: adaptive video up to ${NetworkSpeed.linkHeight()}p + audio itag ${picked.audio.itag}")
                return MergingMediaSource(true, true, adaptive, source(picked.audio, info.duration, mediaItem, false))
            }
        }
        val video = picked.video
        val audio = picked.audio
        return when {
            video != null && audio != null -> MergingMediaSource(
                true,
                true,
                source(video, info.duration, mediaItem, forceProgressive),
                source(audio, info.duration, mediaItem, forceProgressive),
            )
            picked.muxed != null -> source(picked.muxed, info.duration, mediaItem, forceProgressive)
            !hlsUrl.isNullOrBlank() -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                MediaItem.Builder()
                    .setUri(hlsUrl)
                    .setMediaId(mediaItem.mediaId)
                    .setMediaMetadata(mediaItem.mediaMetadata)
                    .build(),
            )
            else -> fallback.createMediaSource(mediaItem)
        }
    }

    private fun adaptiveVideo(info: org.schabi.newpipe.extractor.stream.StreamInfo, mediaItem: MediaItem): MediaSource? {
        val durationSec = info.duration.takeIf { it > 0 } ?: return null
        val maxTier = NetworkSpeed.linkHeight()
        val ladder = StreamSelection.autoLadder(info, maxTier)
        val manifests = ladder.mapNotNull { stream ->
            runCatching {
                val xml = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, stream.itagItem!!, durationSec)
                DashManifestParser().parse(Uri.parse(stream.content), ByteArrayInputStream(xml.toByteArray()))
            }.getOrNull()
        }
        if (manifests.size < 2) return null
        val representations = manifests.map { it.getPeriod(0).adaptationSets[0].representations[0] }
        val base = manifests.first()
        val manifest = DashManifest(
            base.availabilityStartTimeMs, base.durationMs, base.minBufferTimeMs, false,
            C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, null, null, null, null,
            listOf(Period("0", 0, listOf(AdaptationSet(0, C.TRACK_TYPE_VIDEO, representations, emptyList(), emptyList(), emptyList())))),
        )
        return DashMediaSource.Factory(dataSourceFactory).createMediaSource(manifest, mediaItem)
    }

    private fun source(stream: Stream, durationSec: Long, mediaItem: MediaItem, forceProgressive: Boolean): MediaSource {
        val url = stream.content
        val itag = stream.itagItem
        if (!forceProgressive && itag != null && durationSec > 0) {
            val dash = runCatching {
                val xml = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(url, itag, durationSec)
                val manifest = DashManifestParser().parse(Uri.parse(url), ByteArrayInputStream(xml.toByteArray()))
                DashMediaSource.Factory(dataSourceFactory).createMediaSource(manifest, mediaItem)
            }.getOrNull()
            if (dash != null) return dash
        }

        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem.buildUpon().setUri(url).build())
    }
}
