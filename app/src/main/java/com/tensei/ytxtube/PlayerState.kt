package com.tensei.ytxtube

import android.content.ContentResolver
import android.net.Uri

import android.content.ComponentName
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext





@Stable
@OptIn(UnstableApi::class)
class PlayerState(private val scope: CoroutineScope) {
    var controller by mutableStateOf<MediaController?>(null)
        internal set


    var current by mutableStateOf<VideoItem?>(null)
        private set
    var details by mutableStateOf<VideoDetails?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    var speed by mutableFloatStateOf(1f)
        private set
    var loopEnabled by mutableStateOf(false)
        private set
    var shuffleEnabled by mutableStateOf(false)
        private set
    var isMusicMode by mutableStateOf(false)
    var autoPlayEnabled by mutableStateOf(true)
        private set
    var videoZoom by mutableFloatStateOf(1f)
    var zoomToFill by mutableStateOf(false)

    fun resetZoom() { videoZoom = 1f; zoomToFill = false }

    fun toggleZoomToFill() {
        zoomToFill = !zoomToFill
        actionMessage = if (zoomToFill) "Zoomed to fill screen" else "Fit to screen"
    }

    fun toggleAutoPlay() {
        autoPlayEnabled = !autoPlayEnabled
        actionMessage = if (autoPlayEnabled) "Autoplay on" else "Autoplay off"
    }

    fun toggleShuffle() {
        val player = controller ?: return
        val next = !player.shuffleModeEnabled
        player.shuffleModeEnabled = next
        shuffleEnabled = next
        actionMessage = if (shuffleEnabled) "Shuffle on" else "Shuffle off"
    }

    var canSkipPrevious by mutableStateOf(false)
        private set
    var canSkipNext by mutableStateOf(false)
        private set
    var canSeek by mutableStateOf(false)
        private set

    fun skipNext() {
        val player = controller ?: return
        if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
            player.seekToNextMediaItem()
        }
    }

    fun skipPrevious() {
        val player = controller ?: return
        if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) {
            player.seekToPreviousMediaItem()
        }
    }

    fun toggleLoop() {
        val player = controller ?: return
        val next = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
        player.repeatMode = next
        loopEnabled = next == Player.REPEAT_MODE_ONE
        actionMessage = if (loopEnabled) "Loop on" else "Loop off"
    }
    var speedPanelOpen by mutableStateOf(false)

    var holdSpeedActive by mutableStateOf(false)
        internal set

    var scrubPreviewMs by mutableStateOf<Long?>(null)
        internal set
    var scrubDragging by mutableStateOf(false)
        internal set
    private var resumePlayAfterScrub = false
    private var speedBeforeHold: Float? = null
    var videoControlsVisible by mutableStateOf(false)
        internal set

    init {

        scope.launch {
            snapshotFlow { StreamRecovery.recovering to StreamRecovery.failed }.collect { (_, failed) ->
                syncFromPlayer()
                if (failed != null && failed.first == current?.id) {
                    error = failed.second
                    isBuffering = false
                }
            }
        }
    }
    var videoAspect by mutableFloatStateOf(16f / 9f)
        private set

    private var aspectFromFrames = false

    



    /**
     * Video id for which "Play in background" is on (audio only, keeps playing when the app is
     * left). Session-only: never saved, and dropped when another video is opened.
     */
    var backgroundAudioFor by mutableStateOf<String?>(null)
        private set

    val backgroundAudioActive: Boolean get() = backgroundAudioFor != null && backgroundAudioFor == current?.id

    /** Audio-only presentation: the saved Audio only mode or "Play in background" for this video. */
    val audioOnlyActive: Boolean get() = AudioOnly.enabled || backgroundAudioActive

    val stageAspect: Float get() = if (audioOnlyActive) AudioStageAspect else {
        min(videoAspect.coerceIn(StageMinAspect, StageMaxAspect), StageFrameAspect)
    }
    var firstFrameRendered by mutableStateOf(false)
        private set

    var playingFormat by mutableStateOf<String?>(null)
        private set

    /** Audio format actually being decoded, e.g. "AAC · 129 kbps"; null until tracks are known. */
    var playingAudioFormat by mutableStateOf<String?>(null)
        private set

    var decodedHeight by mutableStateOf<Int?>(null)
        private set

    var expanded by mutableStateOf(false)

    var fullscreen by mutableStateOf(false)

    /** Portrait videos go edge-to-edge without rotating the device, exactly like YouTube. */
    var portraitFullscreen by mutableStateOf(false)

    val portraitVideo get() = videoAspect < 1.02f

    var sheet by mutableStateOf<PlayerSheet?>(null)

    var downloadFor by mutableStateOf<VideoItem?>(null)

    val comments = CommentsState(scope)

    /** Polled only while a live stream's chat sheet is open. */
    val liveChat = LiveChatState(scope)



    var accountState by mutableStateOf<VideoAccountState?>(null)
        private set
    private var originalLike = LikeStatus.None
    var inWatchLater by mutableStateOf(false)
        private set

    /** Audio tracks offered by the current video, Original first. */
    var audioOptions by mutableStateOf<List<AudioTrackOption>>(emptyList())
        private set

    /** The cue text Media3's text renderer is currently showing, or null when captions are off. */
    var captionText by mutableStateOf<String?>(null)
        private set

    private var likeOverrides by mutableStateOf<Map<String, LikeStatus>>(emptyMap())
    private var likeDeltas by mutableStateOf<Map<String, Long>>(emptyMap())
    private var dislikeDeltas by mutableStateOf<Map<String, Long>>(emptyMap())

    val currentLike: LikeStatus
        get() = current?.id?.let { likeOverrides[it] } ?: accountState?.like ?: LikeStatus.None

    var actionMessage by mutableStateOf<String?>(null)

    private var loadJob: Job? = null

    private var pendingStart: VideoItem? = null





    private val known = HashMap<String, VideoItem>()

    var queue by mutableStateOf<List<VideoItem>>(emptyList())
        private set

    var musicQueue by mutableStateOf<List<VideoItem>>(emptyList())
        private set

    fun open(item: VideoItem, expand: Boolean = true) {
        isMusicMode = false
        AudioOnly.set(false)
        runCatching { applyVideoTrack() }
        if (expand) expanded = true
        // TV: open straight into fullscreen like YouTube; Back drops to the watch page.
        if (expand && TvMode.isTv) fullscreen = true
        if (current?.id == item.id && error == null) return
        known[item.id] = item
        show(item, startPlayback = true)
    }

    internal fun openFromHistory(entry: WatchEntry) {
        val saved = entry.video
        val localFile = saved.localUri?.toUri()?.path?.let { java.io.File(it) }
        val item = if (localFile?.isFile == false) saved.copy(localUri = null) else saved
        if (current?.id == item.id && error == null) {
            expanded = true
            play()
            return
        }
        startAt = item.id to entry.resumePositionMs
        open(item)
    }

    fun openMusic(item: VideoItem, expand: Boolean = true) {
        openMusicQueue(listOf(item), 0, expand)
    }

    



    fun openMusicQueue(items: List<VideoItem>, index: Int, expand: Boolean = true) {
        val item = items.getOrNull(index) ?: return
        isMusicMode = true
        applyVideoTrack()
        musicQueue = items.drop(index + 1).filter { it.id != item.id }
        if (expand) expanded = true
        if (current?.id == item.id && error == null) {
            publishUpNext(item.id)
            return
        }
        known[item.id] = item
        show(item, startPlayback = true)
    }

    fun playNextMusic(item: VideoItem) {
        val player = controller ?: return
        known[item.id] = item
        if (current == null || player.mediaItemCount == 0) { openMusic(item, expand = false); return }
        player.addMediaItem(player.currentMediaItemIndex + 1, mediaItemFor(item))
        actionMessage = "Playing next"
        continueIfEnded(player)
    }

    




    private var openedAt = 0L

    private fun show(item: VideoItem, startPlayback: Boolean) {
        endHoldSpeed()
        openedAt = android.os.SystemClock.elapsedRealtime()
        Log.i("YTXTubeTime", "open ${item.id}")
        // "Play in background" belongs to one video only.
        if (backgroundAudioFor != null && backgroundAudioFor != item.id) clearBackgroundAudio()
        if (startPlayback) Net.prewarmMediaConnection()
        current = item
        if (isMusicMode) RecentMusic.record(item)
        details = null
        error = null
        errorFromPlayback = false
        firstFrameRendered = false
        videoAspect = 16f / 9f
        aspectFromFrames = false
        decodedHeight = null
        playingFormat = null
        playingAudioFormat = null
        clearLiveQuality()
        loading = true
        sheet = null
        comments.clearFor(item.id)
        liveChat.stop()
        accountState = null
        inWatchLater = false
        audioOptions = emptyList()
        captionText = null
        speedPanelOpen = false
        videoZoom = 1f
        portraitFullscreen = false
        loadJob?.cancel()
        if (item.localUri != null) {
            details = VideoDetails(
                item = item,
                description = "",
                likes = 0,
                dislikes = -1,
                subscribers = 0,
                related = emptyList(),
                playable = true,
                quality = null,
                qualities = emptyList(),
                aspect = null,
            )
            loading = false
            if (startPlayback) startPlayback(item) else publishUpNext(item.id)
            return
        }
        if (Account.signedIn) {
            scope.launch {
                runCatching { InnerTube.videoState(item.id) }
                    .onSuccess { if (current?.id == item.id) { accountState = it; originalLike = it.like } }
                    .onFailure { Log.w(TAG, "account state for ${item.id}", it) }
            }
        }
        loadJob = scope.launch {
            runCatching { YouTube.details(item.id) }
                .onSuccess { d ->
                    if (!isActive || current?.id != item.id) return@launch
                    Log.i("YTXTubeTime", "details +${android.os.SystemClock.elapsedRealtime() - openedAt}ms")
                    details = d
                    refreshTracks()

                    if (!aspectFromFrames) d.aspect?.let { videoAspect = it }


                    comments.load(item.id)
                    known[item.id] = d.item
                    d.related.forEach { known[it.id] = it }
                    if (!d.playable) error = "No playable stream was found for this video."
                    else if (startPlayback) startPlayback(d.item)
                    else publishUpNext(item.id)

                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val dislikes = fetchDislikeCount(item.id)
                        if (current?.id == item.id && dislikes >= 0) {
                            details = details?.copy(dislikes = dislikes)
                        }
                    }
                }
                .onFailure {
                    if (!isActive || current?.id != item.id) return@launch
                    Log.w(TAG, "details failed for ${item.id}", it)
                    error = YouTube.friendlyError(it)

                    comments.load(item.id)
                }
            loading = false
        }
    }

    private fun mediaItemFor(item: VideoItem): MediaItem {
        val localUri = item.localUri
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.channel)
            .setArtworkUri(item.thumbnail?.toUri())
            .setExtras(android.os.Bundle().apply {
                putBoolean(MEDIA_MUSIC_MODE, isMusicMode)
                if (localUri != null) putString("local_uri", localUri)
            })
            .build()
        return MediaItem.Builder()
            .setMediaId(item.id)
            .setMediaMetadata(metadata)
            .apply {
                if (localUri != null) {


                    setRequestMetadata(
                        MediaItem.RequestMetadata.Builder().setMediaUri(localUri.toUri()).build(),
                    )
                }
            }
            .build()
    }

    private fun startPlayback(item: VideoItem) {
        val player = controller ?: run { pendingStart = item; return }

        val handOff = startAt?.takeIf { it.first == item.id }?.second
        startAt = null
        setCurrentMedia(player, item, handOff ?: ResumeStore.startPosition(item.id), play = true)
        refreshSubtitleSelection()
        refreshTracks()
    }

    




    private fun setCurrentMedia(
        player: MediaController,
        item: VideoItem,
        positionMs: Long,
        replace: Boolean = false,
        play: Boolean = false,
    ) {
        player.sendCustomCommand(START_VIDEO_COMMAND, android.os.Bundle().apply {
            putBundle("item", mediaItemFor(item).toBundle(MediaLibraryInfo.INTERFACE_VERSION))
            putLong("position", positionMs)
            putBoolean("replace", replace)
            putBoolean("play", play)
            if (!replace) suggestedNext()?.let { putBundle("next", mediaItemFor(it).toBundle(MediaLibraryInfo.INTERFACE_VERSION)) }
        })
    }

    fun playNext(item: VideoItem) {
        val player = controller ?: return
        known[item.id] = item
        if (current == null || player.mediaItemCount == 0) { open(item, expand = false); return }
        player.addMediaItem(player.currentMediaItemIndex + 1, mediaItemFor(item))
        actionMessage = "Playing next"
        continueIfEnded(player)
    }


    private fun continueIfEnded(player: MediaController) {
        if (player.playbackState == Player.STATE_ENDED && player.currentMediaItemIndex + 1 < player.mediaItemCount) {
            player.seekTo(player.currentMediaItemIndex + 1, 0L)
            player.play()
        }
    }

    fun addToQueue(item: VideoItem) {
        val player = controller ?: return
        known[item.id] = item
        if (current == null || player.mediaItemCount == 0) { open(item, expand = false); return }
        player.addMediaItem(mediaItemFor(item))
        actionMessage = "Added to queue"
        continueIfEnded(player)
    }


    fun playFromQueue(index: Int) {
        val player = controller ?: return
        player.seekTo(player.currentMediaItemIndex + 1 + index, 0L)
        player.play()
    }

    fun removeFromQueue(index: Int) {
        val player = controller ?: return
        player.removeMediaItem(player.currentMediaItemIndex + 1 + index)
    }

    fun clearQueue() {
        val player = controller ?: return
        val from = player.currentMediaItemIndex + 1
        if (from < player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount)
    }

    private fun rebuildQueue() {
        val player = controller ?: return
        val from = player.currentMediaItemIndex + 1
        queue = (from until player.mediaItemCount).map { i -> itemFor(player.getMediaItemAt(i)) }
    }

    private fun itemFor(media: MediaItem): VideoItem = known[media.mediaId] ?: VideoItem(
        id = media.mediaId,
        title = media.mediaMetadata.title?.toString().orEmpty(),
        channel = media.mediaMetadata.artist?.toString().orEmpty(),
        channelUrl = null,
        thumbnail = media.mediaMetadata.artworkUri?.toString(),
        avatar = null,
        durationSec = 0,
        views = -1,
        uploaded = null,
    ).also { known[media.mediaId] = it }

    private fun suggestedNext(): VideoItem? {
        val curr = current ?: return null
        if (isMusicMode) musicQueue.firstOrNull()?.let { return it }
        val related = details?.related.orEmpty().filter { it.id != curr.id }
        return if (isMusicMode) {
            related.firstOrNull { isMusicTrack(it) } ?: related.firstOrNull()
        } else {
            related.firstOrNull { it.channel.equals(curr.channel, ignoreCase = true) } ?: related.firstOrNull()
        }
    }

    private fun publishUpNext(id: String) {
        controller?.sendCustomCommand(SET_UP_NEXT_COMMAND, android.os.Bundle().apply {
            putString("current", id)
            suggestedNext()?.let { putBundle("next", mediaItemFor(it).toBundle(MediaLibraryInfo.INTERFACE_VERSION)) }
        })
    }

    private fun autoPlayNext() {
        if (canSkipNext) {
            skipNext()
            return
        }
        val curr = current ?: return
        val next = suggestedNext()
        if (next != null) {
            actionMessage = "Up next: ${next.title}"
            if (isMusicMode) openMusic(next, expand = expanded) else open(next, expand = expanded)
        } else if (details == null) {
            scope.launch {
                runCatching { YouTube.details(curr.id) }
                    .onSuccess {
                        if (!isActive || current?.id != curr.id) return@onSuccess
                        details = it
                        val related = suggestedNext() ?: return@onSuccess
                        if (isMusicMode) openMusic(related, expand = expanded) else open(related, expand = expanded)
                    }
            }
        }
    }

    private fun onPlayerMovedTo(media: MediaItem) {
        if (media.mediaId.isBlank() || media.mediaId == current?.id) return
        media.mediaMetadata.extras?.takeIf { it.containsKey(MEDIA_MUSIC_MODE) }?.let { extras ->
            isMusicMode = extras.getBoolean(MEDIA_MUSIC_MODE)
            applyVideoTrack()
        }
        if (isMusicMode) {
            val consumed = musicQueue.indexOfFirst { it.id == media.mediaId }
            if (consumed >= 0) musicQueue = musicQueue.drop(consumed + 1)
        }
        show(itemFor(media), startPlayback = false)
    }

    val segments: List<SponsorBlock.Segment>
        get() = current?.id?.let { SponsorBlock.forUi[it] }.orEmpty()


    val askSegment: SponsorBlock.Segment?
        get() = if (!SponsorBlock.enabled) null else segments.firstOrNull {
            it.action == SponsorBlock.Action.Button && positionMs in it.startMs until it.endMs - 500
        }

    fun skipSegment(segment: SponsorBlock.Segment) = seekTo(segment.endMs)

    fun undoSkip(skip: SponsorBlock.Skip) {
        if (skip.videoId == current?.id) seekTo(skip.segment.startMs)
        SponsorBlock.lastSkip = null
    }


    fun refreshAccountState() {
        val item = current ?: return
        accountState = null
        inWatchLater = false
        if (!Account.signedIn) return
        scope.launch {
            runCatching { InnerTube.videoState(item.id) }
                .onSuccess { if (current?.id == item.id) { accountState = it; originalLike = it.like } }
                .onFailure { Log.w(TAG, "account state for ${item.id}", it) }
        }
    }


    val displayLikes: Long
        get() {
            val base = details?.likes ?: return -1
            val delta = current?.id?.let { likeDeltas[it] } ?: 0L
            return (base + delta).coerceAtLeast(0)
        }

    val displayDislikes: Long
        get() {
            val base = details?.dislikes ?: return -1
            if (base < 0) return -1
            val delta = current?.id?.let { dislikeDeltas[it] } ?: 0L
            return (base + delta).coerceAtLeast(0)
        }

    private fun likeWeight(status: LikeStatus): Long = when (status) {
        LikeStatus.Like -> 1L
        LikeStatus.Dislike -> -1L
        LikeStatus.None -> 0L
    }

    private fun dislikeWeight(status: LikeStatus): Long = if (status == LikeStatus.Dislike) 1L else 0L

    /**
     * Tapping a thumb is optimistic for everyone, signed in or not. The visible count moves
     * immediately; the network call only decides whether the choice survives a restart.
     */
    fun setLike(target: LikeStatus) {
        val id = current?.id ?: return
        val before = currentLike
        val next = if (before == target) LikeStatus.None else target
        likeOverrides = likeOverrides + (id to next)
        likeDeltas = likeDeltas + (id to ((likeDeltas[id] ?: 0L) + likeWeight(next) - likeWeight(before)))
        dislikeDeltas = dislikeDeltas + (id to ((dislikeDeltas[id] ?: 0L) + dislikeWeight(next) - dislikeWeight(before)))
        actionMessage = when (next) {
            LikeStatus.Like -> "Liked"
            LikeStatus.Dislike -> "Not for me"
            LikeStatus.None -> "Rating removed"
        }

        if (!Account.signedIn) return
        val previousAccount = accountState
        accountState = previousAccount?.copy(like = next)
        scope.launch {
            runCatching { InnerTube.setLike(id, next) }
                .onFailure {
                    if (current?.id == id) {
                        likeOverrides = likeOverrides + (id to before)
                        likeDeltas = likeDeltas + (id to ((likeDeltas[id] ?: 0L) - likeWeight(next) + likeWeight(before)))
                        dislikeDeltas = dislikeDeltas + (id to ((dislikeDeltas[id] ?: 0L) - dislikeWeight(next) + dislikeWeight(before)))
                        accountState = previousAccount
                        actionMessage = "Couldn't update your like."
                    }
                }
        }
    }

    fun toggleLike() = setLike(LikeStatus.Like)

    fun toggleDislike() = setLike(LikeStatus.Dislike)

    fun toggleSubscribe() {
        val before = accountState ?: return
        val channelId = before.channelId
            ?: details?.item?.channelUrl?.substringAfter("/channel/", "")?.takeIf { it.startsWith("UC") }
            ?: return
        val subscribe = before.subscribed != true
        accountState = before.copy(subscribed = subscribe, channelId = channelId)
        scope.launch {
            runCatching { InnerTube.setSubscribed(channelId, subscribe) }
                .onFailure { accountState = before; actionMessage = "Couldn't update your subscription." }
        }
    }

    fun toggleWatchLater() {
        val id = current?.id ?: return
        val add = !inWatchLater
        inWatchLater = add
        scope.launch {
            runCatching { InnerTube.setInWatchLater(id, add) }
                .onSuccess { actionMessage = if (add) "Saved to Watch later" else "Removed from Watch later" }
                .onFailure { inWatchLater = !add; actionMessage = "Couldn't update Watch later."; Log.w(TAG, "watch later $id", it) }
        }
    }


    fun saveToWatchLater(video: VideoItem) {
        scope.launch {
            runCatching { InnerTube.setInWatchLater(video.id, true) }
                .onSuccess {
                    if (current?.id == video.id) inWatchLater = true
                    actionMessage = "Saved to Watch later"
                }
                .onFailure { actionMessage = "Couldn't save to Watch later."; Log.w(TAG, "watch later ${video.id}", it) }
        }
    }

    



    fun setQuality(height: Int?, preferHdr: Boolean = QualityPreference.hdr) {
        QualityPreference.set(height, preferHdr)
        val player = controller ?: return
        val item = details?.item ?: current ?: return

        // The service rebuilds the source from the exact rendition StreamSelection
        // picked. Forcing a min/max height here would filter that rendition out when
        // an HDR row falls back to a lower SDR height, leaving the player with no
        // video track at all.
        if (item.isLive) {
            // Live used to only relabel the quality while playback stayed on Auto. Now the
            // chosen rendition of the HLS stream is actually pinned (or Auto restored).
            val applied = applyLiveQuality(height)
            details = details?.copy(quality = applied?.let { "${it}p" } ?: "Live")
            return
        }

        val position = player.currentPosition
        setCurrentMedia(player, item, position, replace = true)
        val info = StreamCache.get(item.id) ?: return
        val picked = StreamSelection.pick(info)
        details = details?.copy(
            quality = picked.playedHeight?.let { "${it}p${if (picked.hdr) " HDR" else ""}" },
        )
    }

    /** Quality tiers the current live stream really offers, highest first; empty when not live. */
    var liveQualities by mutableStateOf<List<Int>>(emptyList())
        private set

    /** A live video track is pinned through a track-selection override. */
    private var liveOverride = false

    private fun liveVideoTracks(): Pair<androidx.media3.common.Tracks.Group, List<Int>>? {
        val group = controller?.currentTracks?.groups?.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return null
        val tiers = (0 until group.length).map { i ->
            val f = group.getTrackFormat(i)
            if (group.isTrackSupported(i) && f.height > 0) qualityTier(f.width, f.height) else 0
        }
        return group to tiers
    }

    /** Pins the live rendition closest to [height] (null = Auto). Returns the tier now pinned. */
    private fun applyLiveQuality(height: Int?): Int? {
        val player = controller ?: return null
        val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        var pinned: Int? = null
        if (height != null) {
            liveVideoTracks()?.let { (group, tiers) ->
                LiveQuality.pickTrack(tiers, height)?.let { index ->
                    params.addOverride(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index))
                    pinned = tiers[index]
                }
            }
        }
        liveOverride = pinned != null
        player.trackSelectionParameters = params.build()
        return pinned
    }

    private fun clearLiveQuality() {
        liveQualities = emptyList()
        if (!liveOverride) return
        liveOverride = false
        controller?.let {
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        }
    }

    /** Refresh the audio-track list from the resolved stream info. */
    fun refreshTracks() {
        val id = current?.id ?: return
        val cached = StreamCache.get(id)
        if (cached != null) {
            syncAudioPreference(cached)
            audioOptions = StreamSelection.audioOptions(cached)
            refreshSubtitleSelection()
            return
        }
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { StreamCache.getOrLoad(id) }.getOrNull()
            } ?: return@launch
            if (current?.id != id) return@launch
            syncAudioPreference(info)
            audioOptions = StreamSelection.audioOptions(info)
            refreshSubtitleSelection()
        }
    }

    /** A saved language that this video does not offer falls back to Original, not silence. */
    private fun syncAudioPreference(info: org.schabi.newpipe.extractor.stream.StreamInfo) {
        val wanted = AudioTrackPreference.key ?: return
        val offered = StreamSelection.audioTracks(info)
        if (offered.none { it.key == wanted || wanted.startsWith(it.key) }) AudioTrackPreference.reset()
    }

    /**
     * Swap the audio rendition without losing the place. The service re-reads the persisted
     * preference when it builds the next media source.
     */
    fun setAudioTrack(option: AudioTrackOption?) {
        AudioTrackPreference.set(if (option == null || option.isOriginal) null else option.key, option?.label ?: "Original")
        val player = controller ?: return
        val item = details?.item ?: current ?: return
        val position = player.currentPosition
        setCurrentMedia(player, item, position, replace = true)
        actionMessage = "Audio: ${option?.label ?: "Original"}"
    }

    var selectedSubtitleLanguage by mutableStateOf<String?>(null)
        private set

    private var activeCues by mutableStateOf<List<ParsedCue>>(emptyList())
    private var subtitleFetchJob: kotlinx.coroutines.Job? = null
    private var translatedCaptionVideoId: String? = null

    private fun refreshSubtitleSelection() {
        if (translatedCaptionVideoId != null) {
            if (translatedCaptionVideoId != current?.id) setSubtitleLanguage(null)
            return
        }
        selectedSubtitleLanguage?.let { setSubtitleLanguage(it) }
    }

    internal fun translateCaptions(sourceUrl: String, language: String, unavailableMessage: String) {
        val videoId = current?.id ?: return
        val url = translatedCaptionUrl(sourceUrl, language) ?: return
        subtitleFetchJob?.cancel()
        subtitleFetchJob = scope.launch {
            val cues = withContext(Dispatchers.IO) { SubtitleParser.fetchAndParse(url) }
            showExternalCaptions(videoId, language, cues, unavailableMessage)
        }
    }

    internal fun importCaptions(resolver: ContentResolver, uri: Uri, videoId: String, errorMessage: String) {
        if (current?.id != videoId) return
        subtitleFetchJob?.cancel()
        subtitleFetchJob = scope.launch {
            val cues = withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openInputStream(uri)?.use { SubtitleParser.readSubtitleFile(it) }.orEmpty()
                }.getOrDefault(emptyList())
            }
            showExternalCaptions(videoId, "und", cues, errorMessage)
        }
    }

    private fun showExternalCaptions(videoId: String, language: String, cues: List<ParsedCue>, errorMessage: String) {
        if (current?.id != videoId) return
        if (cues.isEmpty()) {
            actionMessage = errorMessage
            return
        }
        val player = controller ?: return
        translatedCaptionVideoId = videoId
        selectedSubtitleLanguage = language
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        activeCues = cues
        val pos = player.currentPosition
        captionText = cues.firstOrNull { pos in it.startMs..it.endMs }?.text
    }

    fun setSubtitleLanguage(code: String?, trackUrl: String? = null) {
        translatedCaptionVideoId = null
        selectedSubtitleLanguage = code
        subtitleFetchJob?.cancel()
        activeCues = emptyList()
        captionText = null
        val p = controller
        try {
            if (code == null) {
                p?.trackSelectionParameters = p?.trackSelectionParameters
                    ?.buildUpon()
                    ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    ?.build() ?: return
                activeCues = emptyList()
                captionText = null
                actionMessage = "Captions off"
                return
            } else {
                p?.trackSelectionParameters = p?.trackSelectionParameters
                    ?.buildUpon()
                    ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    ?.setPreferredTextLanguage(code)
                    ?.build() ?: return
                actionMessage = "Captions on"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting subtitle language", e)
        }

        val videoId = current?.id ?: return
        val url = trackUrl ?: run {
            val info = StreamCache.get(videoId)
            info?.subtitles?.firstOrNull { it.languageTag == code }?.content
                ?: CaptionTracks.get(videoId)?.firstOrNull { it.languageTag == code }?.url
        }

        if (url != null) {
            subtitleFetchJob = scope.launch(Dispatchers.IO) {
                val cues = SubtitleParser.fetchAndParse(url)
                withContext(Dispatchers.Main) {
                    activeCues = cues
                    val pos = controller?.currentPosition ?: 0L
                    captionText = cues.firstOrNull { pos in it.startMs..it.endMs }?.text
                }
            }
        }
    }

    fun setCaptions(on: Boolean) {
        if (!on) {
            setSubtitleLanguage(null)
        } else {
            val videoId = current?.id
            val info = videoId?.let { StreamCache.get(it) }
            val firstCode = info?.subtitles?.firstOrNull { it.isUrl && it.content.isNotBlank() }?.languageTag
                ?: CaptionTracks.get(videoId.orEmpty())?.firstOrNull()?.languageTag
                ?: "en"
            setSubtitleLanguage(firstCode)
        }
    }


    fun retry() = current?.let { item -> StreamCache.invalidate(item.id); current = null; open(item) }

    fun togglePlay() {
        val player = controller ?: return
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause()
        else play()
    }

    fun play() {
        val player = controller ?: return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun seekBy(deltaMs: Long): Long {
        val player = controller ?: return 0L
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return 0L
        val from = player.currentPosition
        seekTo(from + deltaMs)
        return positionMs - from
    }

    fun seekTo(ms: Long) {
        val player = controller ?: return
        val max = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = ms.coerceIn(0L, max)
        positionMs = target
        player.seekTo(target)
    }

    fun beginScrubDragging() {
        if (scrubDragging) return
        val player = controller
        resumePlayAfterScrub = player?.isPlaying == true
        if (resumePlayAfterScrub) player?.pause()
        scrubDragging = true
    }


    fun endScrubDragging(seekMs: Long? = null) {
        if (!scrubDragging) return
        val player = controller
        val resume = resumePlayAfterScrub
        resumePlayAfterScrub = false
        scrubDragging = false
        scrubPreviewMs = null
        if (seekMs != null) seekTo(seekMs)
        if (resume) player?.play()
    }


    fun beginHoldSpeed(): Boolean {
        val player = controller ?: return false
        if (scrubDragging || speedBeforeHold != null) return false
        if (!player.isPlaying) return false
        speedBeforeHold = player.playbackParameters.speed
        holdSpeedActive = true
        previewSpeed(2f)
        return true
    }

    fun endHoldSpeed() {
        val restore = speedBeforeHold ?: return
        speedBeforeHold = null
        holdSpeedActive = false
        previewSpeed(restore)
    }


    fun previewSpeed(value: Float) {
        val player = controller ?: return
        val s = PlaybackSpeed.snap(value)
        if (player.playbackParameters.speed != s) player.setPlaybackSpeed(s)
        speed = s
    }

    




    fun commitSpeed(value: Float) {
        previewSpeed(value)
        val s = PlaybackSpeed.snap(value)
        if (!PlaybackSpeed.remember) return
        if (PlaybackSpeed.isNormal(s) && !PlaybackSpeed.isNormal(PlaybackSpeed.saved)) PlaybackSpeed.setRemember(false, 1f)
        else PlaybackSpeed.save(s)
    }

    fun toggleRememberSpeed() = PlaybackSpeed.setRemember(!PlaybackSpeed.remember, speed)


    /**
     * "Play in background" button: turns audio-only background playback on for the current video
     * (video decoding stops, playback continues when the app is left or the screen locks), or
     * back to normal video. Uses the existing session and track switching; saved settings are
     * not touched and the app is not minimized.
     */
    fun toggleBackgroundAudio() {
        val id = current?.id ?: return
        if (backgroundAudioActive) {
            clearBackgroundAudio()
            actionMessage = "Back to video"
        } else {
            backgroundAudioFor = id
            BackgroundAudioOverride.videoId = id
            applyVideoTrack()
            controller?.let { if (!it.playWhenReady) it.play() }
            actionMessage = "Playing in background"
        }
    }

    /**
     * "Play in background" from Shorts: the Short continues in the main player (the one with the
     * notification, lock-screen and headset controls) as audio only, from the same position.
     * Same per-video, unsaved override as the button on the video screen.
     */
    fun playInBackground(item: VideoItem, positionMs: Long) {
        backgroundAudioFor = item.id
        BackgroundAudioOverride.videoId = item.id
        startAt = item.id to positionMs
        val alreadyOpen = current?.id == item.id && error == null
        open(item, expand = false)
        applyVideoTrack()
        if (alreadyOpen) controller?.let { it.seekTo(positionMs); it.play() }
    }

    /** One-shot start position for [startPlayback] (Short handed off mid-way). */
    private var startAt: Pair<String, Long>? = null

    private fun clearBackgroundAudio() {
        if (backgroundAudioFor == null && BackgroundAudioOverride.videoId == null) return
        backgroundAudioFor = null
        BackgroundAudioOverride.videoId = null
        applyVideoTrack()
    }

    fun setAudioOnly(on: Boolean) {
        AudioOnly.set(on)
        applyVideoTrack()
    }

    




    fun setBackground(
        background: Boolean,
        changingConfigurations: Boolean = false,
    ) {
        if (background) {
            val action = BackgroundPlaybackPolicy.onBackground(
                backgroundEnabled = BackgroundPlaybackPolicy.effectiveEnabled(
                    BackgroundPlaybackSettings.enabled,
                    BackgroundAudioOverride.videoId,
                    controller?.currentMediaItem?.mediaId,
                ),
                changingConfigurations = changingConfigurations,
                hasMedia = (controller?.mediaItemCount ?: 0) > 0,
            )
            if (action == BackgroundPlaybackPolicy.OnBackground.None && changingConfigurations) return
            if (action == BackgroundPlaybackPolicy.OnBackground.Pause) {
                // Background playback is off: pause instead of continuing audio-only. This
                // goes through the session, so the notification and lock screen stay in sync.
                Log.i(TAG, "app left the foreground with background playback off; pausing")
                controller?.pause()
            }
        }
        if (wantBackground == background) return
        wantBackground = background
        if (isMusicMode) return
        applyVideoTrack()
    }

    





    private var wantBackground = false

    /** [error] came from the player (not from loading details), so recovery may clear it. */
    private var errorFromPlayback = false


    private fun applyVideoTrack() {
        runCatching {
            // Music keeps its video track alive in the background. Toggling it off and on
            // made the decoder re-init, which is the audible dip heard when reopening the
            // app during background playback.
            if (isMusicMode) return
            val off = AudioOnly.enabled || backgroundAudioActive || wantBackground
            val player = controller ?: return
            if (player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO) == off) return
            Log.i(TAG, if (off) "video off (${if (AudioOnly.enabled) "audio only" else "background"})" else "video on")
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, off)
                .build()
        }
    }

    fun close() {
        LiveVideoTranslation.stop()
        clearBackgroundAudio()
        loadJob?.cancel()
        controller?.run { stop(); clearMediaItems() }
        queue = emptyList()
        expanded = false
        fullscreen = false
        portraitFullscreen = false
        sheet = null
        current = null
        details = null
        error = null
        loading = false
    }

    




    internal fun onConnected() {
        syncFromPlayer()
        applyVideoTrack()
        controller?.videoSize?.let(::takeVideoSize)
        pendingStart?.let { pendingStart = null; startPlayback(it); return }
        val player = controller ?: return
        rebuildQueue()
        val playing = player.currentMediaItem ?: return
        if (current != null || playing.mediaId.isBlank()) return
        val meta = playing.mediaMetadata
        isMusicMode = meta.extras?.getBoolean(MEDIA_MUSIC_MODE) == true
        val item = VideoItem(
            id = playing.mediaId,
            title = meta.title?.toString().orEmpty(),
            channel = meta.artist?.toString().orEmpty(),
            channelUrl = null,
            thumbnail = meta.artworkUri?.toString(),
            avatar = null,
            durationSec = player.duration.takeIf { it > 0 }?.div(1000) ?: 0L,
            views = -1,
            uploaded = null,
        )
        current = item
        loadJob = scope.launch {
            runCatching { YouTube.details(item.id) }.onSuccess {
                if (!isActive || current?.id != item.id) return@onSuccess
                details = it
                current = it.item
                refreshTracks()
                publishUpNext(item.id)
            }
        }
    }

    internal fun syncFromPlayer() {
        val player = controller ?: return
        isPlaying = player.isPlaying
        canSeek = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        canSkipPrevious = player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        canSkipNext = player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)

        isBuffering = player.playbackState == Player.STATE_BUFFERING || StreamRecovery.recovering == current?.id
        positionMs = player.currentPosition.coerceAtLeast(0L)
        bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
        durationMs = player.duration.takeIf { it > 0 } ?: ((current?.durationSec ?: 0L) * 1000L)
        speed = player.playbackParameters.speed
        loopEnabled = player.repeatMode == Player.REPEAT_MODE_ONE

        if (selectedSubtitleLanguage != null && activeCues.isNotEmpty()) {
            val pos = positionMs
            captionText = activeCues.firstOrNull { pos in it.startMs..it.endMs }?.text
        } else if (selectedSubtitleLanguage == null) {
            captionText = null
        }
    }

    internal val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            LiveVideoTranslation.playing = isPlaying
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) LiveVideoTranslation.seek()
        }
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer()
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) rebuildQueue()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && errorFromPlayback) {
                // The service recovered by itself (network back, stream re-extracted).
                errorFromPlayback = false
                error = null
            }
            if (playbackState == Player.STATE_ENDED) {
                val p = controller ?: return
                if (p.repeatMode == Player.REPEAT_MODE_ONE) return
                if (p.hasNextMediaItem()) {
                    skipNext()
                } else if (autoPlayEnabled) {
                    autoPlayNext()
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) LiveVideoTranslation.stop()
            if (mediaItem != null) onPlayerMovedTo(mediaItem)
            if (selectedSubtitleLanguage != null) {
                refreshSubtitleSelection()
            } else {
                captionText = null
                activeCues = emptyList()
            }
        }

        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            if (translatedCaptionVideoId != null) return
            val text = cueGroup.cues
                .mapNotNull { cue -> cue.text?.toString()?.takeIf { it.isNotBlank() } }
                .joinToString("\n")
            if (text.isNotBlank()) {
                captionText = text
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = takeVideoSize(videoSize)

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            playingAudioFormat = describeAudio(tracks)
            if (current?.isLive == true) {
                val offered = liveVideoTracks()?.second?.let(LiveQuality::offered).orEmpty()
                if (offered != liveQualities) liveQualities = offered
                // A saved quality applies to live too, once its renditions are known.
                val saved = QualityPreference.height
                if (saved != null && !liveOverride && offered.isNotEmpty()) {
                    applyLiveQuality(saved)?.let { details = details?.copy(quality = "${it}p") }
                }
            }
            val vs = controller?.videoSize ?: return
            if (vs.width <= 0 || vs.height <= 0) return
            val w = (vs.width * vs.pixelWidthHeightRatio).roundToInt()
            describeFormat(w, vs.height)
        }

        override fun onRenderedFirstFrame() {
            Log.i("YTXTubeTime", "first frame +${android.os.SystemClock.elapsedRealtime() - openedAt}ms")
            firstFrameRendered = true
        }

        override fun onPlayerError(e: PlaybackException) {
            val status = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            Log.w(TAG, "playback error ${e.errorCodeName}${status?.let { " (HTTP $it)" } ?: ""} for ${current?.id}", e)

            if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && StreamRecovery.recovering == current?.id) return
            errorFromPlayback = true
            error = when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Lost the connection. Playback resumes when you're back online, or try again."
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "YouTube refused the stream. Try again."
                else -> "This video couldn't be played."
            }
        }
    }

    private fun describeAudio(tracks: androidx.media3.common.Tracks): String? {
        val format = tracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            .firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }
            ?: return null
        val codec = DownloadFormats.audioCodecLabel(format.codecs)
            ?: when (format.sampleMimeType) {
                androidx.media3.common.MimeTypes.AUDIO_AAC -> "AAC"
                androidx.media3.common.MimeTypes.AUDIO_OPUS -> "Opus"
                else -> null
            }
        val bitrate = format.averageBitrate.takeIf { it > 0 } ?: format.bitrate.takeIf { it > 0 } ?: format.peakBitrate
        val kbps = DownloadFormats.kbps(bitrate)
        return listOfNotNull(codec, kbps.takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" · ").ifBlank { null }
    }

    private fun takeVideoSize(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        aspectFromFrames = true

        val w = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt()
        describeFormat(w, videoSize.height)
    }


    private fun describeFormat(width: Int, height: Int) {
        val tier = qualityTier(width, height)
        if (decodedHeight != tier) {
            Log.i("YTXTubeTime", "now ${tier}p (${width}×${height}) at +${android.os.SystemClock.elapsedRealtime() - openedAt}ms")
        }
        decodedHeight = tier
        val formats = controller?.currentTracks?.groups.orEmpty()
            .filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
            .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it) } }
        val format = formats.firstOrNull { it.height == height }
        if (format == null) {
            playingFormat = "${tier}p"
            return
        }
        run {
            val codec = when {
                format.codecs?.startsWith("avc") == true -> "H.264"
                format.codecs?.startsWith("vp9") == true || format.codecs?.startsWith("vp09") == true -> "VP9"
                format.codecs?.startsWith("av01") == true -> "AV1"
                else -> format.codecs ?: format.sampleMimeType ?: "?"
            }
            val auto = if (formats.size > 1) "Auto" else null
            val labelTier = qualityTier(format.width, format.height)
            val codecText = format.codecs?.lowercase(java.util.Locale.US).orEmpty()
            val hdr = format.colorInfo?.let {
                it.colorTransfer == C.COLOR_TRANSFER_ST2084 || it.colorTransfer == C.COLOR_TRANSFER_HLG ||
                    it.hdrStaticInfo != null
            } == true || codecText.contains("vp09.02") || codecText.contains("vp9.2") ||
                (codecText.startsWith("av01") &&
                    (codecText.split('.').getOrNull(3)?.take(2)?.toIntOrNull() ?: 0) >= 10)
            playingFormat = listOfNotNull(
                auto,
                "${labelTier}p",
                if (hdr) "HDR" else null,
                codec,
                format.id?.substringAfterLast(":")?.let { "itag $it" },
            ).joinToString(" · ")
        }
    }
}

@Composable
fun rememberPlayerState(): PlayerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { PlayerState(scope) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { controller ->
                state.controller = controller
                controller.addListener(state.listener)
                state.onConnected()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            state.controller?.removeListener(state.listener)
            MediaController.releaseFuture(future)
            state.controller = null
        }
    }

    LaunchedEffect(state.isPlaying) {
        while (isActive && state.isPlaying) {
            state.syncFromPlayer()
            delay(250)
        }
    }
    return state
}

enum class PlayerSheet { Quality, Comments, Queue, Settings, Speed, Audio, Subtitles, Translate, LiveTranslate }

private const val TAG = "YTXTube"
private const val MEDIA_MUSIC_MODE = "music_mode"
