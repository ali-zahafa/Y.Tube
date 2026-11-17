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
 * This mode does not claim ExoPlayer's offline downloads or background features.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun YouTubeWebPlayerDialog(videoId: String, onClose: () -> Unit) {
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
                Text("YouTube Web Player", color = Color.White, modifier = Modifier.padding(10.dp))
                Button(onClick = onClose) { Text("Back to ExoPlayer") }
            }
            val context = androidx.compose.ui.platform.LocalContext.current
            val view = remember(videoId, context) {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webChromeClient = object : WebChromeClient() {
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
                    webViewClient = object : WebViewClient() {
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
                    loadUrl("https://m.youtube.com/watch?v=$videoId")
                }
            }
            DisposableEffect(view) {
                onDispose {
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenView = null
                    fullscreenCallback = null
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
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
