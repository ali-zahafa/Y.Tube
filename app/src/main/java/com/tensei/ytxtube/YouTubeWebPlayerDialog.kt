package com.tensei.ytxtube

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.net.Uri

/**
 * Optional YouTube website player. ExoPlayer remains the default player.
 * The page is isolated in its own WebView and navigation is restricted to YouTube.
 * Background mode retains this exact page in WebPlaybackService; it never re-extracts a URL.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun YouTubeWebPlayerDialog(videoId: String, startPositionMs: Long = 0, onBackground: () -> Unit, onClose: () -> Unit) {
    var pageError by remember(videoId) { mutableStateOf<String?>(null) }
    var fullscreenView by remember(videoId) { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember(videoId) { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    BackHandler {
        if (fullscreenView != null) {
            fullscreenCallback?.onCustomViewHidden()
            fullscreenView = null
            fullscreenCallback = null
        } else onClose()
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Web Player", color = Color.White, modifier = Modifier.padding(10.dp))
                Button(onClick = onClose) { Text("Back to ExoPlayer") }
            }
            val context = androidx.compose.ui.platform.LocalContext.current
            val view = remember(videoId, context) {
                if (WebPlaybackSession.videoId != videoId) {
                    WebPlaybackSession.stop()
                    WebPlaybackSession.destroy()
                }
                WebPlaybackSession.view ?: BackgroundPlaybackWebView(context.applicationContext).apply {
                    WebPlaybackSession.view = this
                    WebPlaybackSession.videoId = videoId
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    loadUrl("https://m.youtube.com/watch?v=$videoId&t=${startPositionMs.coerceAtLeast(0) / 1000}s")
                }
            }
                view.webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            if (view == null || fullscreenView != null) {
                                callback?.onCustomViewHidden()
                                return
                            }
                            fullscreenView = view
                            fullscreenCallback = callback
                        }

                        override fun onHideCustomView() {
                            fullscreenView = null
                            fullscreenCallback = null
                        }
                    }
                view.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            pageError = null
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                pageError = "YouTube page failed to load."
                            }
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url?.startsWith("https://m.youtube.com/") == true) {
                                view?.evaluateJavascript(AD_STATE_OBSERVER, null)
                            }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri: Uri = request?.url ?: return true
                            val host = uri.host?.lowercase() ?: return true
                            return uri.scheme != "https" || !(host == "youtube.com" || host.endsWith(".youtube.com"))
                        }
                    }

            DisposableEffect(view) {
                WebPlaybackSession.attached = true
                view.onResume()
                onDispose {
                    WebPlaybackSession.attached = false
                    // Do not retain callbacks/closures that belong to the dismissed dialog.
                    view.webChromeClient = WebChromeClient()
                    view.webViewClient = BackgroundWebViewClient()
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenView = null
                    fullscreenCallback = null
                    if (!WebPlaybackSession.background) {
                        WebPlaybackSession.stop()
                        WebPlaybackSession.destroy()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    startWebBackground(context, view,
                        onStarted = {
                            fullscreenCallback?.onCustomViewHidden()
                            fullscreenView = null
                            onBackground()
                        },
                        onError = { pageError = it },
                    )
                }) { Text("Background playback") }
                if (WebPlaybackSession.background) {
                    Button(onClick = { WebPlaybackSession.stop(); onClose() }) { Text("Stop background") }
                }
            }
            if (pageError != null) {
                Text(pageError ?: "", color = Color.White, modifier = Modifier.padding(8.dp))
                Button(onClick = { pageError = null; view.reload() }) { Text("Retry web player") }
            }
            if (fullscreenView != null) {
                AndroidView(
                    factory = { ctx -> FrameLayout(ctx) },
                    update = { frame ->
                        val custom = fullscreenView
                        frame.removeAllViews()
                        if (custom != null) {
                            (custom.parent as? android.view.ViewGroup)?.removeView(custom)
                            frame.addView(custom, FrameLayout.LayoutParams(-1, -1))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private val AD_STATE_OBSERVER = """
(function () {
  if (window.__ytubeAdObserver) return;
  const state = { timer: null, video: null, rate: 1, muted: false };
  window.__ytubeAdObserver = state;
  function restore() {
    if (!state.video) return;
    try {
      if (state.video.playbackRate === 4) state.video.playbackRate = state.rate;
      state.video.muted = state.muted;
    } catch (_) {}
    state.video = null;
  }
  function check() {
    const root = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
    const video = root && root.querySelector('video');
    const ad = !!root && (root.classList.contains('ad-showing') || root.classList.contains('ad-interrupting'));
    if (!ad || !video) { restore(); return; }
    if (state.video !== video) {
      restore();
      state.video = video;
      state.rate = video.playbackRate;
      state.muted = video.muted;
    }
    try { video.muted = true; video.playbackRate = 4; } catch (_) {}
  }
  state.timer = window.setInterval(check, 250);
  document.addEventListener('visibilitychange', function () { if (document.hidden) restore(); });
  window.addEventListener('pagehide', function () { restore(); clearInterval(state.timer); });
  check();
})();
""".trimIndent()


// Installed only after an explicit user gesture. Reversible: the original getters are
// used whenever background mode is disabled; user Pause is never overridden.
internal val BACKGROUND_MODE_SCRIPT = """
(function () {
  window.__ytubeBackground = true;
  if (window.__ytubeVisibilityInstalled) return;
  window.__ytubeVisibilityInstalled = true;
  ['hidden', 'visibilityState'].forEach(function (key) {
    const descriptor = Object.getOwnPropertyDescriptor(Document.prototype, key);
    if (!descriptor || !descriptor.get) return;
    try {
      Object.defineProperty(document, key, { configurable: true, get: function () {
        return window.__ytubeBackground ? (key === 'hidden' ? false : 'visible') : descriptor.get.call(document);
      }});
    } catch (_) {}
  });
  document.addEventListener('visibilitychange', function (event) {
    if (window.__ytubeBackground) event.stopImmediatePropagation();
  }, true);
})();
""".trimIndent()


/** Retained pages must keep navigation restrictions without retaining Compose callbacks. */
private class BackgroundWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return true
        val host = uri.host?.lowercase() ?: return true
        return uri.scheme != "https" || !(host == "youtube.com" || host.endsWith(".youtube.com"))
    }
    override fun onPageFinished(view: WebView?, url: String?) {
        if (WebPlaybackSession.background && url?.startsWith("https://m.youtube.com/") == true) {
            view?.evaluateJavascript(BACKGROUND_MODE_SCRIPT, null)
        }
    }
}


/** Shared by the button and on-device regression tests; success requires playing media. */
internal fun startWebBackground(
    context: android.content.Context,
    view: WebView,
    onStarted: () -> Unit,
    onError: (String) -> Unit,
) {
    view.evaluateJavascript("(function(){const v=document.querySelector('video'); return !!v && !v.paused && !v.ended && v.readyState >= 2;})()") { result ->
        if (result != "true") {
            onError("Start the video on the page first, then tap Background playback.")
        } else if (WebPlaybackSession.view !== view) {
            onError("This web player has already closed. Open it and try again.")
        } else {
            view.evaluateJavascript(BACKGROUND_MODE_SCRIPT, null)
            WebPlaybackSession.background = true
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(context,
                    android.content.Intent(context, WebPlaybackService::class.java))
            }.onSuccess { onStarted() }.onFailure {
                WebPlaybackSession.background = false
                view.evaluateJavascript("window.__ytubeBackground = false;", null)
                onError("Could not start background playback. Keep the player open and try again.")
            }
        }
    }
}
