package com.tensei.freetube

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TikTokScreen(padding: PaddingValues, onBackground: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val arabic = LanguageSettings.effectiveLanguageCode() == "ar"
    fun label(en: String, ar: String) = if (arabic) ar else en
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf("https://www.tiktok.com/") }
    var loading by remember { mutableStateOf(true) }
    var webError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var offered by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseFormat by rememberSaveable { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    val view = remember {
        WebPlaybackSession.view?.takeIf { WebPlaybackSession.videoId == "tiktok-guest" }
            ?: BackgroundPlaybackWebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = true
            }
    }
    fun work(action: () -> String, success: (String) -> Unit, failure: String) {
        scope.launch {
            busy = true
            message = ""
            try { success(withContext(Dispatchers.IO) { action() }) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = failure }
            finally { busy = false }
        }
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) work({ TikTokScreenshot.read(context, uri) }, { result ->
            if (result.isBlank()) message = label("No readable text in this image.", "مفيش نص واضح في الصورة.")
            else { query = result; message = label("Review the text and choose a search.", "راجع النص واختار نوع البحث.") }
        }, label("Could not read image. First use requires internet for language files.", "تعذرت قراءة الصورة. أول استخدام يحتاج إنترنت لتنزيل ملفات اللغة."))
    }
    val visualSearch = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri) ?: "image/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("screenshot", uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent,
                label("Choose a visual search app", "اختار تطبيق بحث بالصور")))
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { dst ->
        val src = source
        if (dst != null && src != null) work({ TikTokFiles.saveAudio(context, Uri.parse(src), dst); "" },
            { message = label("Audio saved.", "تم حفظ الصوت.") }, label("Could not export AAC audio.", "تعذّر استخراج صوت AAC."))
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { source = uri.toString(); export.launch("freetube-audio.m4a") }
    }
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { dst ->
        val url = offered
        if (url != null && dst != null) work({ TikTokFiles.saveVideo(context, url, dst); "" },
            { message = label("Video saved.", "تم حفظ الفيديو.") }, label("Download unavailable.", "تعذّر حفظ الفيديو."))
        offered = null
    }

    val audioDownload = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { dst ->
        val url = offered
        if (url != null && dst != null) work({ TikTokFiles.downloadAudio(context, url, dst); "" },
            { message = label("Audio saved.", "تم حفظ الصوت.") }, label("Could not save AAC audio.", "تعذّر حفظ صوت AAC."))
        offered = null
    }
    if (chooseFormat) AlertDialog(
        onDismissRequest = { chooseFormat = false; offered = null },
        title = { Text(label("Save download as", "حفظ التنزيل بصيغة")) },
        confirmButton = { TextButton(onClick = {
            chooseFormat = false
            download.launch("freetube-video.mp4")
        }) { Text("MP4") } },
        dismissButton = { TextButton(onClick = {
            chooseFormat = false
            audioDownload.launch("freetube-audio.m4a")
        }) { Text("M4A") } }
    )

    DisposableEffect(view) {
        if (WebPlaybackSession.view === view) WebPlaybackSession.attached = true
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && !TikTokGuest.allowed(request.url.toString())
            override fun onPageStarted(v: WebView, url: String?, icon: android.graphics.Bitmap?) {
                loading = true
                webError = null
            }
            override fun onPageFinished(v: WebView, url: String?) { loading = false }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    loading = false
                    webError = error.description.toString()
                }
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, reload: Boolean) {
                canGoBack = v.canGoBack()
                if (url != null && TikTokGuest.allowed(url)) page = url
            }
        }
        view.setDownloadListener { url, _, _, mime, _ ->
            if (mime?.substringBefore(';') == "video/mp4" && TikTokFiles.downloadable(url)) {
                offered = url
                chooseFormat = true
            } else message = label("No supported video download offered.", "الصفحة لم تُتح تنزيل فيديو مدعوم.")
        }
        if (view.url == null && TikTokGuest.allowed(page)) view.loadUrl(page)
        else loading = view.progress < 100
        onDispose {
            view.setDownloadListener(null)
            view.webViewClient = WebViewClient()
            if (WebPlaybackSession.view === view) {
                WebPlaybackSession.attached = false
                if (!WebPlaybackSession.background) WebPlaybackSession.destroy()
            } else view.destroy()
        }
    }
    fun search(videos: Boolean) {
        val url = TikTokGuest.destination(query, videos)
        if (url == null) message = label("Enter a name or TikTok link.", "اكتب اسم أو رابط تيك توك.")
        else { message = ""; page = url; view.loadUrl(url) }
    }
    fun seek(seconds: Int) {
        view.evaluateJavascript("(function(){const v=$WEB_MEDIA_ELEMENT; if(!v || !Number.isFinite(v.duration)) return false; v.currentTime=Math.min(v.duration,Math.max(0,v.currentTime+$seconds));return true;})()") {
            if (it != "true") message = label("Open and play a video first.", "افتح فيديو وشغّله الأول.")
        }
    }
    BackHandler(enabled = canGoBack) { view.goBack() }
    Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
        OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            label = { Text(label("Name, @username or TikTok link", "اسم، يوزرنيم أو رابط تيك توك")) })
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { search(false) }) { Text(label("Accounts", "حسابات")) }
            TextButton(onClick = { search(true) }) { Text(label("Videos", "فيديوهات")) }
            TextButton(onClick = { photo.launch("image/*") }, enabled = !busy) { Text(label("Read screenshot", "قراءة لقطة شاشة")) }
            TextButton(onClick = { visualSearch.launch("image/*") }) {
                Text(label("Search image with…", "بحث بالصورة باستخدام…"))
            }
            TextButton(onClick = { pickVideo.launch(arrayOf("video/*")) }, enabled = !busy) { Text(label("Extract local audio", "استخراج صوت ملف")) }
        }
        webError?.let { error ->
            Text(label("Could not load TikTok", "تعذّر فتح تيك توك") + " ($error)", Modifier.padding(horizontal = 8.dp))
            TextButton(onClick = { view.loadUrl(page) }) { Text(label("Retry", "حاول تاني")) }
        }
        if (busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotEmpty()) Text(message, Modifier.padding(horizontal = 8.dp))
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().weight(1f))
        if (page.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { seek(-10) }) { Text("−10 s") }
            TextButton(onClick = { seek(10) }) { Text("+10 s") }
            TextButton(onClick = {
                view.evaluateJavascript("(function(){const v=$WEB_MEDIA_ELEMENT;return !!v && !v.paused && !v.ended;})()") { playing ->
                    if (playing != "true") message = label("Start the video first.", "شغّل الفيديو الأول.")
                    else if (WebPlaybackSession.view != null && WebPlaybackSession.view !== view) {
                        message = label("Close the other web player first.", "اقفل مشغل الويب الآخر الأول.")
                    } else {
                        WebPlaybackSession.view = view
                        WebPlaybackSession.videoId = "tiktok-guest"
                        WebPlaybackSession.attached = true
                        startWebBackground(context, view, onBackground) { message = it }
                    }
                }
            }) { Text(label("Background", "تشغيل في الخلفية")) }
        }
    }
}
