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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.HttpDataSource
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
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import android.media.audiofx.LoudnessEnhancer
import androidx.compose.runtime.snapshotFlow
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.Stream
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

private const val ACTION_CLOSE = "com.tensei.ytxtube.CLOSE"
internal val START_VIDEO_COMMAND = SessionCommand("com.tensei.ytxtube.START_VIDEO", Bundle.EMPTY)
internal val SET_UP_NEXT_COMMAND = SessionCommand("com.tensei.ytxtube.SET_UP_NEXT", Bundle.EMPTY)
private val CLOSE_COMMAND = SessionCommand(ACTION_CLOSE, Bundle.EMPTY)

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var streamRecoverer: StreamRecoverer? = null
    private var mediaCache: SimpleCache? = null
    private var audioBoost: LoudnessEnhancer? = null
    private val effectsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sessionPlayer: SessionPlayer
    private val resolver = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))

    override fun onCreate() {
        super.onCreate()

        mediaCache = runCatching {
            SimpleCache(
                File(cacheDir, "video-on-demand"),
                LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024),
                StandaloneDatabaseProvider(this),
            )
        }.onFailure { Log.w("YTXTube", "Playback cache unavailable", it) }.getOrNull()
        val sources = YouTubeMediaSourceFactory(this, mediaCache)
        val renderers = SyncedRenderersFactory(this).apply { setEnableDecoderFallback(true) }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(sources)
            .setBandwidthMeter(PlaybackBandwidth.get())
            .setTrackSelector(
                DefaultTrackSelector(
                    this,
                    AdaptiveTrackSelection.Factory(
                        10_000,
                        25_000,
                        25_000,
                        0.75f,
                    ),
                ).apply {
                    setParameters(
                        buildUponParameters()
                            .setAllowVideoMixedDecoderSupportAdaptiveness(true)
                            .setAllowVideoNonSeamlessAdaptiveness(false),
                    )
                },
            )
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setAllocator(androidx.media3.exoplayer.upstream.DefaultAllocator(true, 64 * 1024))
                    .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
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
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                applyAudioBoost(audioSessionId)
            }
        })
        effectsScope.launch {
            snapshotFlow { SuperPlayerSettings.skipSilence to SuperPlayerSettings.audioBoost }
                .collect { (skipSilence, _) ->
                    player.skipSilenceEnabled = skipSilence
                    applyAudioBoost(player.audioSessionId)
                }
        }
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

        streamRecoverer = StreamRecoverer(player, sources, resolver, mediaCache).also(player::addListener)
        networkRecoverer = NetworkRecoverer(this, player).also(player::addListener)
        resumeSaver = ResumeSaver(player).also(player::addListener)
        historyTracker = WatchHistoryTracker(player)
        watchTime = WatchTimeReporter(player).also(player::addListener)
        sessionPlayer = SessionPlayer(player)
        session = MediaSession.Builder(this, sessionPlayer)
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
    private var historyTracker: WatchHistoryTracker? = null
    private var watchTime: WatchTimeReporter? = null
    private var networkRecoverer: NetworkRecoverer? = null
    private var sponsorSkipper: SponsorSkipper? = null

    private fun applyAudioBoost(sessionId: Int) {
        releaseAudioBoost()
        if (!SuperPlayerSettings.audioBoost || sessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            val effect = LoudnessEnhancer(sessionId)
            audioBoost = effect
            effect.setTargetGain(300)
            if (effect.setEnabled(true) != android.media.audiofx.AudioEffect.SUCCESS) releaseAudioBoost()
        } catch (error: RuntimeException) {
            releaseAudioBoost()
            Log.w("YTXTube", "Audio boost unavailable for this device or audio output", error)
        }
    }

    private fun releaseAudioBoost() {
        val effect = audioBoost ?: return
        audioBoost = null
        runCatching { effect.release() }
    }

    override fun onDestroy() {
        streamRecoverer?.release()
        streamRecoverer = null
        effectsScope.cancel()
        releaseAudioBoost()
        resumeSaver?.saveNow()
        sessionPlayer.cancelPendingSkip()
        historyTracker?.release()
        watchTime?.finish()
        watchTime?.release()
        networkRecoverer?.release()
        sponsorSkipper?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        mediaCache?.release()
        mediaCache = null
        resolver.shutdownNow()
        super.onDestroy()
    }

    private inner class SessionPlayer(private val engine: ExoPlayer) : ForwardingSimpleBasePlayer(engine) {
        private val main = Handler(Looper.getMainLooper())
        private var upNext: Pair<String, MediaItem>? = null
        private var resolving: ListenableFuture<MediaItem>? = null

        private val candidate: MediaItem?
            get() = upNext?.takeIf { it.first == engine.currentMediaItem?.mediaId }?.second

        init {
            engine.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    cancelPendingSkip()
                }
            })
        }

        override fun getState(): State {
            val state = super.getState()
            val commands = state.availableCommands.buildUpon()
            if (!engine.hasPreviousMediaItem()) commands.remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            if (!engine.hasNextMediaItem()) {
                commands.remove(Player.COMMAND_SEEK_TO_NEXT)
                if (candidate != null) {
                    commands.add(Player.COMMAND_SEEK_TO_NEXT)
                    commands.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
            }
            return state.buildUpon().setAvailableCommands(commands.build()).build()
        }

        fun setUpNext(fromId: String, next: MediaItem?) {
            if (fromId != engine.currentMediaItem?.mediaId) return
            val nextItem = next?.takeIf { it.mediaId.isNotBlank() && it.mediaId != fromId }
            if (nextItem?.mediaId == candidate?.mediaId) return
            cancelPendingSkip()
            upNext = nextItem?.let { fromId to it }
            invalidateState()
        }

        fun startVideo(item: MediaItem, positionMs: Long, replace: Boolean, play: Boolean): Boolean {
            val sameVideo = engine.currentMediaItem?.mediaId == item.mediaId
            if (replace && !sameVideo) return false
            cancelPendingSkip()
            if (engine.mediaItemCount == 0) {
                engine.setMediaItem(item, positionMs)
            } else {
                val index = engine.currentMediaItemIndex
                engine.addMediaItem(index + 1, item)
                engine.seekTo(index + 1, positionMs)
                if (sameVideo) engine.removeMediaItem(index)
                val excess = engine.currentMediaItemIndex - 30
                if (excess > 0) engine.removeMediaItems(0, excess)
            }
            engine.prepare()
            if (play) engine.play()
            return true
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    if (engine.hasPreviousMediaItem()) {
                        engine.seekToPreviousMediaItem()
                        engine.play()
                    }
                }
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    if (engine.hasNextMediaItem()) {
                        engine.seekToNextMediaItem()
                        engine.play()
                    } else {
                        resolveNext()
                    }
                }
                else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            }
            return Futures.immediateVoidFuture()
        }

        private fun resolveNext() {
            val next = candidate ?: return
            if (resolving != null) return
            val task = resolver.submit<MediaItem> {
                if (next.mediaMetadata.extras?.getString("local_uri") == null) {
                    StreamCache.getOrLoad(next.mediaId)
                }
                playableItem(next)
            }
            resolving = task
            task.addListener({
                main.post {
                    if (resolving !== task) return@post
                    resolving = null
                    val resolved = runCatching { task.get() }.getOrElse {
                        Log.w("YTXTube", "Could not resolve the next video", it)
                        return@post
                    }
                    if (engine.hasNextMediaItem()) {
                        engine.seekToNextMediaItem()
                        engine.play()
                    } else {
                        startVideo(resolved, ResumeStore.startPosition(resolved.mediaId), replace = false, play = true)
                    }
                }
            }, MoreExecutors.directExecutor())
        }

        fun cancelPendingSkip() {
            val task = resolving
            resolving = null
            task?.cancel(false)
        }
    }

    private fun playableItem(item: MediaItem): MediaItem {
        val uri = item.mediaMetadata.extras?.getString("local_uri") ?: YouTube.watchUrl(item.mediaId)
        return item.buildUpon()
            .setUri(uri)
            .setMediaMetadata(item.mediaMetadata.buildUpon().setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO).build())
            .build()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(CLOSE_COMMAND)
                .apply {
                    if (controller.packageName == packageName) {
                        add(START_VIDEO_COMMAND)
                        add(SET_UP_NEXT_COMMAND)
                    }
                }
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(
                    ImmutableList.of(
                        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                            .setDisplayName("Previous video")
                            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                            .setSlots(CommandButton.SLOT_BACK)
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_NEXT)
                            .setDisplayName("Next video")
                            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                            .setSlots(CommandButton.SLOT_FORWARD)
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                            .setDisplayName("Rewind 10 seconds")
                            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                            .setSlots(CommandButton.SLOT_OVERFLOW)
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                            .setDisplayName("Forward 10 seconds")
                            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                            .setSlots(CommandButton.SLOT_OVERFLOW)
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
            if (controller.packageName == packageName) {
                when (customCommand) {
                    START_VIDEO_COMMAND -> {
                        val item = args.getBundle("item")?.let { MediaItem.fromBundle(it, MediaLibraryInfo.INTERFACE_VERSION) }
                            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        val started = sessionPlayer.startVideo(
                            playableItem(item), args.getLong("position"),
                            args.getBoolean("replace"), args.getBoolean("play"),
                        )
                        if (!started) {
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                        }
                        if (!args.getBoolean("replace")) {
                            sessionPlayer.setUpNext(item.mediaId, args.getBundle("next")?.let { MediaItem.fromBundle(it, MediaLibraryInfo.INTERFACE_VERSION) })
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    SET_UP_NEXT_COMMAND -> {
                        val fromId = args.getString("current")
                            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        sessionPlayer.setUpNext(fromId, args.getBundle("next")?.let { MediaItem.fromBundle(it, MediaLibraryInfo.INTERFACE_VERSION) })
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
            }
            if (customCommand.customAction == ACTION_CLOSE) {
                closeFromNotification()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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

internal object MediaAudio {
    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
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
        Net.mediaUserAgent(url)?.let { return it }
        val parsed = Uri.parse(url)
        val clientParam = parsed.getQueryParameter("c").orEmpty().uppercase()
        val clientVersion = parsed.getQueryParameter("cver")?.takeIf { it.isNotBlank() }
        return when {
            clientParam == "VISIONOS" -> YoutubeParsingHelper.getVisionOsUserAgent(null)
            clientParam.contains("IOS") -> clientVersion?.let {
                "com.google.ios.youtube/$it (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
            } ?: UA_IOS
            clientParam.contains("ANDROID_VR") -> clientVersion?.let {
                "com.google.android.apps.youtube.vr.oculus/$it (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            } ?: UA_ANDROID_VR
            clientParam.contains("ANDROID") -> clientVersion?.let {
                "com.google.android.youtube/$it (Linux; U; Android 14) gzip"
            } ?: UA_ANDROID
            clientParam.contains("TVHTML5") -> UA_TV
            else -> Net.USER_AGENT
        }
    }

    fun createDataSourceFactory(context: Context): DataSource.Factory {
        val dynamicOkHttpFactory = object : DataSource.Factory {
            override fun createDataSource(): DataSource {
                val base = OkHttpDataSource.Factory(Net.mediaClient).createDataSource()
                return object : DataSource by base {
                    override fun open(dataSpec: DataSpec): Long {
                        val url = dataSpec.uri.toString()
                        val ua = Net.mediaUserAgent(url) ?: userAgentForUrl(url)
                        val headers = HashMap(dataSpec.httpRequestHeaders)
                        headers.keys.removeAll { it.equals("User-Agent", ignoreCase = true) }
                        headers["User-Agent"] = ua
                        val clientName = Uri.parse(url).getQueryParameter("c")?.uppercase().orEmpty()
                        val isWebClient = clientName == "WEB" || clientName == "MWEB" ||
                            clientName == "WEB_EMBEDDED_PLAYER" || (clientName.isBlank() && ua == Net.USER_AGENT)
                        if (isWebClient) {
                            headers.putIfAbsent("Origin", "https://www.youtube.com")
                            headers.putIfAbsent("Referer", "https://www.youtube.com/")
                        }
                        val cookies = if (Account.signedIn) Account.cookieHeader() else ""
                        if (cookies.isNotBlank()) {
                            headers.keys.removeAll { it.equals("Cookie", ignoreCase = true) }
                            headers["Cookie"] = cookies
                        }
                        val updatedSpec = dataSpec.buildUpon()
                            .setHttpRequestHeaders(headers)
                            .build()
                        try {
                            return base.open(updatedSpec)
                        } catch (error: HttpDataSource.InvalidResponseCodeException) {
                            val parsed = Uri.parse(url)
                            Log.e(
                                "YTXTubeHTTP",
                                "Media HTTP ${error.responseCode}; host=${parsed.host}; client=${parsed.getQueryParameter("c") ?: "mapped"}; cver=${parsed.getQueryParameter("cver") ?: "mapped"}; itag=${parsed.getQueryParameter("itag") ?: "?"}; range=${dataSpec.position}+${dataSpec.length}",
                            )
                            throw error
                        }
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
private class YouTubeMediaSourceFactory(context: Context, cache: SimpleCache?) : MediaSource.Factory {
    private val dataSourceFactory: DataSource.Factory = YouTubeStreamHttp.createDataSourceFactory(context)
    private val onDemandDataSourceFactory: DataSource.Factory = cache?.let {
        CacheDataSource.Factory()
            .setCache(it)
            .setUpstreamDataSourceFactory(dataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    } ?: dataSourceFactory
    private val fallback = DefaultMediaSourceFactory(dataSourceFactory)
    private var recoveryPolicy: LoadErrorHandlingPolicy? = null

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { fallback.setDrmSessionManagerProvider(provider) }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        val recoveryAwarePolicy = object : LoadErrorHandlingPolicy by policy {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val status = generateSequence(info.exception as Throwable?) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode
                return if (status == 403 || status == 410) C.TIME_UNSET
                else policy.getRetryDelayMsFor(info)
            }
        }
        recoveryPolicy = recoveryAwarePolicy
        fallback.setLoadErrorHandlingPolicy(recoveryAwarePolicy)
    }

    override fun getSupportedTypes(): IntArray = fallback.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        buildSource(mediaItem)

    private fun buildSource(mediaItem: MediaItem): MediaSource {
        if (mediaItem.localConfiguration?.uri?.scheme == "file") {
            return fallback.createMediaSource(mediaItem)
        }
        PlayerFallback.directStreams(mediaItem.mediaId)?.let { direct ->
            val hls = direct.hlsUrl
            if (direct.muxedUrl == null && (direct.videoUrl == null || direct.audioUrl == null) && hls != null) {
                return hlsFactory().createMediaSource(mediaItem.buildUpon().setUri(hls).build())
            }
            // Prefer the complete progressive stream when offered. A valid muxed
            // URL should not be hidden by a failing adaptive video/audio pair.
            if (direct.muxedUrl != null) return directSource(direct.muxedUrl, mediaItem)
            if (direct.videoUrl != null && direct.audioUrl != null) {
                return MergingMediaSource(
                    true,
                    true,
                    directSource(direct.videoUrl, mediaItem),
                    directSource(direct.audioUrl, mediaItem),
                )
            }
            if (hls != null) return hlsFactory().createMediaSource(mediaItem.buildUpon().setUri(hls).build())
        }
        val info = StreamCache.get(mediaItem.mediaId) ?: return fallback.createMediaSource(mediaItem)
        val isLive = info.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM
        val hlsUrl = info.hlsUrl?.ifBlank { null }
        val route = StreamRecovery.routeFor(mediaItem.mediaId)
        if ((isLive || route == StreamRecovery.Route.Hls) && !hlsUrl.isNullOrBlank()) {
            Log.i("YTXTube", "media source ${mediaItem.mediaId}: playing HLS")
            return hlsFactory().createMediaSource(
                MediaItem.Builder()
                    .setUri(hlsUrl)
                    .setMediaId(mediaItem.mediaId)
                    .setMediaMetadata(mediaItem.mediaMetadata)
                    .build(),
            )
        }
        val picked = StreamSelection.pick(info)
        if (route == StreamRecovery.Route.Muxed && picked.muxed != null) {
            return source(picked.muxed, info.duration, mediaItem)
        }

        if (picked.video == null && picked.muxed == null && !hlsUrl.isNullOrBlank()) {
            Log.i("YTXTube", "media source ${mediaItem.mediaId}: no progressive streams, falling back to HLS")
            return hlsFactory().createMediaSource(
                MediaItem.Builder()
                    .setUri(hlsUrl)
                    .setMediaId(mediaItem.mediaId)
                    .setMediaMetadata(mediaItem.mediaMetadata)
                    .build(),
            )
        }

        // YouTube progressive URLs are complete media files, not stable DASH segment bases.
        // Synthetic byte-range DASH manifests caused mid-stream HTTP 403 responses.
        val video = picked.video
        val audio = picked.audio
        return when {
            video != null && audio != null -> MergingMediaSource(
                true,
                true,
                source(video, info.duration, mediaItem),
                source(audio, info.duration, mediaItem),
            )
            picked.muxed != null -> source(picked.muxed, info.duration, mediaItem)
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

    private fun directSource(url: String, mediaItem: MediaItem): MediaSource =
        ProgressiveMediaSource.Factory(dataSourceFactory)
            .also { factory -> recoveryPolicy?.let(factory::setLoadErrorHandlingPolicy) }
            .createMediaSource(mediaItem.buildUpon().setUri(url).build())

    private fun hlsFactory(): HlsMediaSource.Factory =
        HlsMediaSource.Factory(dataSourceFactory).also { factory ->
            recoveryPolicy?.let(factory::setLoadErrorHandlingPolicy)
        }

    private fun source(stream: Stream, durationSec: Long, mediaItem: MediaItem): MediaSource {
        val factory = if (durationSec > 0) onDemandDataSourceFactory else dataSourceFactory
        return ProgressiveMediaSource.Factory(factory)
            .also { progressive -> recoveryPolicy?.let(progressive::setLoadErrorHandlingPolicy) }
            .createMediaSource(mediaItem.buildUpon().setUri(stream.content).build())
    }
}
