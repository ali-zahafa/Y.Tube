package com.ytube.player.state

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.ytube.player.engine.SponsorSegment
import com.ytube.player.engine.StreamFallbackEngine
import com.ytube.player.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SuperPlayerState(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var mediaController: MediaController? by mutableStateOf(null)
        private set

    var currentVideoId: String by mutableStateOf("")
        private set

    var currentTitle: String by mutableStateOf("")
        private set

    var autoSkipSponsors: Boolean by mutableStateOf(true)
    var useDeArrowTitles: Boolean by mutableStateOf(true)
    var backgroundAudioOnly: Boolean by mutableStateOf(true)

    val sponsorSegments = mutableStateListOf<SponsorSegment>()

    init {
        connectController()
    }

    private fun connectController() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                mediaController = controllerFuture.get()
            } catch (_: Exception) {}
        }, MoreExecutors.directExecutor())
    }

    fun loadAndPlayVideo(
        videoId: String,
        streamUrl: String,
        originalTitle: String,
        author: String,
        thumbnailUrl: String
    ) {
        currentVideoId = videoId
        currentTitle = originalTitle
        sponsorSegments.clear()

        val enhancedUri = StreamFallbackEngine.enhanceStreamUri(Uri.parse(streamUrl))
        val metadata = MediaMetadata.Builder()
            .setTitle(originalTitle)
            .setArtist(author)
            .setArtworkUri(Uri.parse(thumbnailUrl))
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(videoId)
            .setUri(enhancedUri)
            .setMediaMetadata(metadata)
            .build()

        mediaController?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }

        // Asynchronously fetch SponsorBlock segments & DeArrow clean title
        scope.launch {
            val segments = StreamFallbackEngine.fetchSponsorSegments(videoId)
            sponsorSegments.clear()
            sponsorSegments.addAll(segments)

            if (useDeArrowTitles) {
                StreamFallbackEngine.fetchDeArrowTitle(videoId)?.let { cleanTitle ->
                    currentTitle = cleanTitle
                }
            }
        }
    }

    /**
     * Automatically disables video track decoding when app is in background or screen is off.
     */
    fun onAppLifecycleChanged(isInBackground: Boolean) {
        if (!backgroundAudioOnly) return
        val controller = mediaController ?: return
        val params = controller.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isInBackground)
            .build()
        controller.trackSelectionParameters = params
    }

    fun release() {
        mediaController?.release()
        mediaController = null
    }
}
