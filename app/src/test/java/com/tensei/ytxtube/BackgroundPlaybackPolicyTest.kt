package com.tensei.ytxtube

import com.tensei.ytxtube.BackgroundPlaybackPolicy.OnBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPlaybackPolicyTest {

    @Test
    fun backgroundOn_keepsPlayingAudio() {
        assertEquals(
            OnBackground.ContinueAudio,
            BackgroundPlaybackPolicy.onBackground(backgroundEnabled = true, changingConfigurations = false, hasMedia = true),
        )
    }

    @Test
    fun backgroundOff_pauses() {
        assertEquals(
            OnBackground.Pause,
            BackgroundPlaybackPolicy.onBackground(backgroundEnabled = false, changingConfigurations = false, hasMedia = true),
        )
    }

    @Test
    fun configurationChange_isNotLeavingTheApp() {
        for (enabled in listOf(true, false)) {
            assertEquals(
                OnBackground.None,
                BackgroundPlaybackPolicy.onBackground(backgroundEnabled = enabled, changingConfigurations = true, hasMedia = true),
            )
        }
    }

    @Test
    fun nothingLoaded_doesNothing() {
        assertEquals(
            OnBackground.None,
            BackgroundPlaybackPolicy.onBackground(backgroundEnabled = false, changingConfigurations = false, hasMedia = false),
        )
    }

    @Test
    fun playInBackgroundButton_appliesOnlyToItsVideo() {
        // Saved setting on: always on.
        assertTrue(BackgroundPlaybackPolicy.effectiveEnabled(true, null, "abc"))
        // Saved setting off, button pressed for the playing video: on, without touching the setting.
        assertTrue(BackgroundPlaybackPolicy.effectiveEnabled(false, "abc", "abc"))
        // Button was for another video, or not pressed: off.
        assertFalse(BackgroundPlaybackPolicy.effectiveEnabled(false, "abc", "xyz"))
        assertFalse(BackgroundPlaybackPolicy.effectiveEnabled(false, null, "abc"))
        assertFalse(BackgroundPlaybackPolicy.effectiveEnabled(false, "abc", null))
        // With the button on, leaving the app keeps audio playing instead of pausing.
        assertEquals(
            OnBackground.ContinueAudio,
            BackgroundPlaybackPolicy.onBackground(
                backgroundEnabled = BackgroundPlaybackPolicy.effectiveEnabled(false, "abc", "abc"),
                changingConfigurations = false,
                hasMedia = true,
            ),
        )
    }

    @Test
    fun notification_staysWhilePaused_goesWhenEndedOrEmpty() {
        // Paused (READY/BUFFERING with media): the notification must stay so it can be resumed.
        assertTrue(BackgroundPlaybackPolicy.keepNotification(hasMedia = true, idle = false, ended = false))
        assertFalse(BackgroundPlaybackPolicy.keepNotification(hasMedia = true, idle = false, ended = true))
        assertFalse(BackgroundPlaybackPolicy.keepNotification(hasMedia = true, idle = true, ended = false))
        // Closed from the notification: queue cleared.
        assertFalse(BackgroundPlaybackPolicy.keepNotification(hasMedia = false, idle = true, ended = false))
    }

    @Test
    fun taskRemoved_keepsServiceOnlyWhenEnabledAndPlaying() {
        assertTrue(BackgroundPlaybackPolicy.keepServiceOnTaskRemoved(true, playWhenReady = true, hasMedia = true, ended = false))
        assertFalse(BackgroundPlaybackPolicy.keepServiceOnTaskRemoved(false, playWhenReady = true, hasMedia = true, ended = false))
    }

    @Test
    fun taskRemoved_userPausedOrEnded_stopsService() {
        // A manual pause must never be turned back into playback by swiping the app away.
        assertFalse(BackgroundPlaybackPolicy.keepServiceOnTaskRemoved(true, playWhenReady = false, hasMedia = true, ended = false))
        assertFalse(BackgroundPlaybackPolicy.keepServiceOnTaskRemoved(true, playWhenReady = true, hasMedia = true, ended = true))
        assertFalse(BackgroundPlaybackPolicy.keepServiceOnTaskRemoved(true, playWhenReady = true, hasMedia = false, ended = false))
    }
}
