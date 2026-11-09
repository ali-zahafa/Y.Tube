package com.tensei.ytxtube

/**
 * Pure decision logic for what happens to the main player when the app leaves the foreground
 * and when its task is swiped away. Kept free of Android types so it can be unit tested.
 *
 * Audio focus itself is not decided here: the service's ExoPlayer is built with
 * `handleAudioFocus = true`, so Media3 owns focus requests, ducking, transient pauses and
 * resuming only when playback was not paused by the user.
 */
object BackgroundPlaybackPolicy {

    enum class OnBackground {
        /** Nothing to do (nothing loaded, or a configuration change is in progress). */
        None,

        /** Keep playing; the UI may drop the video track so only audio is decoded. */
        ContinueAudio,

        /** Background playback is turned off: pause. */
        Pause,
    }

    /**
     * Called when the activity is stopped (app minimized, screen locked, another app on top).
     *
     * @param backgroundEnabled the user's Background playback setting.
     * @param changingConfigurations the activity is only being recreated; it is not really leaving.
     * @param hasMedia the player has something loaded.
     */
    fun onBackground(
        backgroundEnabled: Boolean,
        changingConfigurations: Boolean,
        hasMedia: Boolean,
    ): OnBackground = when {
        // A visible PiP window keeps the activity started, so ON_STOP never arrives for it;
        // a stop while in PiP means the window was hidden (screen locked) and is treated
        // like any other trip to the background.
        changingConfigurations || !hasMedia -> OnBackground.None
        backgroundEnabled -> OnBackground.ContinueAudio
        else -> OnBackground.Pause
    }

    /**
     * Background playback is on when the saved setting is on, or when "Play in background"
     * was turned on for the video that is currently playing.
     */
    fun effectiveEnabled(savedEnabled: Boolean, overrideVideoId: String?, currentVideoId: String?): Boolean =
        savedEnabled || (overrideVideoId != null && overrideVideoId == currentVideoId)

    /**
     * Whether the playback notification (foreground service) stays up. It stays while something
     * is loaded, playing or paused, and goes only when playback has ended, the queue is empty,
     * or the user closes it, so a paused video can always be resumed from the notification.
     */
    fun keepNotification(hasMedia: Boolean, idle: Boolean, ended: Boolean): Boolean = hasMedia && !idle && !ended

    /**
     * Called when the user swipes the app away from Recents.
     *
     * @return true when the playback service should keep running (and keep its notification),
     * false when it should pause and stop itself.
     */
    fun keepServiceOnTaskRemoved(
        backgroundEnabled: Boolean,
        playWhenReady: Boolean,
        hasMedia: Boolean,
        ended: Boolean,
    ): Boolean = backgroundEnabled && playWhenReady && hasMedia && !ended
}
