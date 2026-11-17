package com.tensei.ytxtube

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real, public user-reported YouTube URLs. No mocked extraction, streams or success. */
@RunWith(AndroidJUnit4::class)
class YouTubePlaybackValidationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { block() } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue("App UI thread did not respond within 15 seconds", done.await(15, TimeUnit.SECONDS))
        failure?.let { throw it }
    }
    private fun evaluate(view: WebView, script: String): String? {
        val done = CountDownLatch(1)
        var answer: String? = null
        main { view.evaluateJavascript(script) { answer = it; done.countDown() } }
        return if (done.await(5, TimeUnit.SECONDS)) answer else null
    }
    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("Input injection failed", instrumentation.uiAutomation.injectInputEvent(event, false))
            event.recycle()
        }
    }

    @Test(timeout = 840_000) fun reportedVideosPlayContentBeyondTheOneMinuteBoundary() {
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        val results = JSONArray()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var state: PlayerState? = null
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        try {
            val connectUntil = SystemClock.elapsedRealtime() + 30_000
            while (state?.controller == null && SystemClock.elapsedRealtime() < connectUntil) {
                main { state = activity?.playerHolder }
                Thread.sleep(250)
            }
            assertTrue("App media controller did not connect", state?.controller != null)
            for (id in listOf("4kPOYZq73hQ", "QiZZJkHcW8A", "CMSplvQ7_0A")) {
                main {
                    state!!.close()
                    StreamCache.invalidate(id)
                    if (state!!.autoPlayEnabled) state!!.toggleAutoPlay()
                    state!!.open(VideoItem(id, "Playback validation $id", "", null, null, null, 0, -1, null))
                }
                val deadline = SystemClock.elapsedRealtime() + 240_000
                var playedMs = 0L
                var priorPosition = 0L
                var priorRoute = ""
                var route = "native"
                var webError = ""
                var lastTap = 0L
                var observedPosition = 0L
                while (SystemClock.elapsedRealtime() < deadline && playedMs < 65_000) {
                    var playing = false
                    var view: WebView? = null
                    main {
                        route = if (state!!.webPlayerEnabled) "web" else "native"
                        observedPosition = state!!.positionMs
                        playing = state!!.isPlaying
                        if (route == "web") view = WebPlaybackSession.view
                    }
                    view?.let { web ->
                        // Reject optional cookies in the isolated guest test browser.
                        evaluate(web, "Array.from(document.querySelectorAll('button')).filter(b=>/^(Reject all|رفض الكل)$/i.test((b.innerText||'').trim())).forEach(b=>b.click());")
                        val raw = evaluate(web, """
                            (function(){const v=document.querySelector('video'); const p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');
                            const ad=!!p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting'));
                            const e=document.querySelector('.ytp-error-content-wrap,.ytm-player-error-message-renderer');
                            const b=Array.from(document.querySelectorAll('button')).find(x=>/^play( video)?$/i.test(x.getAttribute('aria-label')||''));
                            const r=(b||v)?.getBoundingClientRect();
                            return JSON.stringify({t:v?.currentTime||0,playing:!!v&&!v.paused&&!v.ended&&v.videoWidth>0,ad:ad,
                            error:(e?.innerText||(!v?document.body?.innerText:'')||'').slice(0,240),scale:window.devicePixelRatio||1,x:r?r.x+r.width/2:0,y:r?r.y+r.height/2:0});})();
                        """.trimIndent())
                        val decoded = raw?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
                        val page = decoded?.let { runCatching { JSONObject(it) }.getOrNull() }
                        if (page != null) {
                            observedPosition = (page.optDouble("t") * 1000).toLong()
                            playing = page.optBoolean("playing") && !page.optBoolean("ad")
                            webError = page.optString("error")
                            if (!page.optBoolean("playing") && SystemClock.elapsedRealtime() - lastTap > 5000 && page.optDouble("x") > 0) {
                                val location = IntArray(2)
                                main { web.getLocationOnScreen(location) }
                                val scale = page.optDouble("scale", 1.0).toFloat()
                                tap(location[0] + page.optDouble("x").toFloat() * scale,
                                    location[1] + page.optDouble("y").toFloat() * scale)
                                lastTap = SystemClock.elapsedRealtime()
                            }
                        }
                    }
                    if (playing && route == priorRoute) {
                        val delta = observedPosition - priorPosition
                        if (delta in 1L..4000L) playedMs += delta
                    }
                    priorPosition = observedPosition
                    priorRoute = route
                    Thread.sleep(1000)
                }
                val result = JSONObject().put("video_id", id).put("passed", playedMs >= 65_000)
                    .put("content_played_ms", playedMs).put("route", route).put("position_ms", observedPosition)
                    .put("web_error", webError)
                main {
                    result.put("native_error", state!!.controller?.playerError?.errorCodeName.orEmpty())
                    result.put("app_error", state!!.error.orEmpty())
                        .put("fallback_reason", PlayerFallback.lastFailureReason(id).orEmpty())
                        .put("client", PlayerFallback.lastAttemptMetadata(id)?.clientName.orEmpty())
                        .put("po_token_status", YTXTubeApplication.poTokenGenerator.lastStatus.name)
                }
                results.put(result)
                context.filesDir.resolve("youtube-validation.json").writeText(results.toString(2))
                android.util.Log.i("YouTubeValidation", result.toString())
            }
            assertTrue("One or more real YouTube videos failed; see youtube-validation.json. No verified APK may be published.",
                (0 until results.length()).all { results.getJSONObject(it).getBoolean("passed") })
        } finally {
            val responsive = runCatching { main { state?.close(); activity?.finish() } }.isSuccess
            if (responsive) scenario.close()
        }
    }
}
