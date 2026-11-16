package com.tensei.ytxtube

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
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
    BackHandler(onBack = onClose)
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
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
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
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
                }
            }
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        }
    }
}
