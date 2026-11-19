package com.tensei.freetube

import android.app.*
import android.content.*
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// YouTube can keep a paused preview before the active watch video in the DOM.
internal const val WEB_MEDIA_ELEMENT = """(function () {
    const media = Array.from(document.querySelectorAll('video, audio'));
    const active = media.find(v => !v.paused && !v.ended);
    if (active) window.__freetubeSessionMedia = active;
    const retained = window.__freetubeSessionMedia;
    return active || (retained && retained.isConnected && retained) ||
        document.querySelector('#movie_player video') || media[0];
})()"""

/** The same WebView stays alive when its Compose dialog goes away. All access is on main. */
internal object WebPlaybackSession {
    var view: WebView? = null
    var videoId: String? = null
    var background by androidx.compose.runtime.mutableStateOf(false)
    var attached = false
    var service: WebPlaybackService? = null

    fun stop() {
        background = false
        val oldService = service
        service = null
        oldService?.stopSelf()
        view?.let {
            it.settings.mediaPlaybackRequiresUserGesture = true
            it.evaluateJavascript("window.__freetubeBackground = false; document.querySelectorAll('video').forEach(v => v.pause());", null)
            if (!attached) destroy()
        }
    }

    fun destroy() {
        val old = view
        view = null
        videoId = null
        (old?.parent as? ViewGroup)?.removeView(old)
        old?.stopLoading()
        old?.loadUrl("about:blank")
        old?.destroy()
    }
}

/** Foreground lifetime and lock-screen controls for website audio. */
class WebPlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSession
    private var wakeLock: PowerManager.WakeLock? = null
    private var playing = false
    private var pausedSince = 0L
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) command(false)
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!WebPlaybackSession.background) { stopSelf(); return }
            WebPlaybackSession.view?.evaluateJavascript(
                "(function(){const v=$WEB_MEDIA_ELEMENT; return v ? (v.ended ? 'ended' : (!v.paused ? 'playing' : 'paused')) : 'missing';})()"
            ) { result ->
                if (WebPlaybackSession.service !== this@WebPlaybackService) return@evaluateJavascript
                if (result == "\"ended\"" || result == "\"missing\"") {
                    WebPlaybackSession.stop()
                } else {
                    playing = result == "\"playing\""
                    if (playing) pausedSince = 0L
                    else if (pausedSince == 0L) pausedSince = SystemClock.elapsedRealtime()
                    if (pausedSince != 0L && SystemClock.elapsedRealtime() - pausedSince > 5 * 60 * 1000L) {
                        WebPlaybackSession.stop()
                        return@evaluateJavascript
                    }
                    session.setPlaybackState(PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                        .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f).build())
                    if (playing) {
                        if (wakeLock?.isHeld != true) wakeLock?.acquire(10 * 60 * 1000L)
                    } else if (wakeLock?.isHeld == true) wakeLock?.release()
                    getSystemService(NotificationManager::class.java).notify(ID, notification())
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        WebPlaybackSession.service = this
        androidx.core.content.ContextCompat.registerReceiver(this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        session = MediaSession(this, "FreeTubeWeb").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = command(true)
                override fun onPause() = command(false)
                override fun onStop() = WebPlaybackSession.stop()
            })
            isActive = true
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:web-playback")
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Web player playback", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WebPlaybackSession.service = this
        startForeground(ID, notification())
        if (WebPlaybackSession.view == null || !WebPlaybackSession.background) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            "stop" -> WebPlaybackSession.stop()
            "pause" -> command(false)
            else -> command(true)
        }
        handler.removeCallbacks(poll)
        handler.post(poll)
        return START_NOT_STICKY
    }

    private fun command(play: Boolean) {
        // Chromium's media engine owns audio focus; a second native request would
        // steal focus from our own WebView and pause it.
        WebPlaybackSession.view?.evaluateJavascript(
            if (play) "(function(){const v=$WEB_MEDIA_ELEMENT; if(v) v.play().catch(() => {});})()"
            else "(function(){const v=$WEB_MEDIA_ELEMENT; if(v) v.pause();})()", null)
    }

    @Suppress("DEPRECATION")
    private fun notification(): Notification {
        fun action(name: String): PendingIntent = PendingIntent.getService(this, name.hashCode(),
            Intent(this, WebPlaybackService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("freetube Web Player")
            .setContentText(if (playing) "Playing in background" else "Paused — tap Play to resume")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(playing)
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", action(if (playing) "pause" else "play"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action("stop"))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session.release()
        unregisterReceiver(noisyReceiver)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (WebPlaybackSession.service === this) {
            WebPlaybackSession.service = null
            WebPlaybackSession.background = false
            WebPlaybackSession.view?.evaluateJavascript("window.__freetubeBackground = false; document.querySelectorAll('video').forEach(v => v.pause());", null)
            if (!WebPlaybackSession.attached) WebPlaybackSession.destroy()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val ID = 2353
        const val CHANNEL = "web-playback"
    }
}


internal class BackgroundPlaybackWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(if (WebPlaybackSession.background) View.VISIBLE else visibility)
    }
}
