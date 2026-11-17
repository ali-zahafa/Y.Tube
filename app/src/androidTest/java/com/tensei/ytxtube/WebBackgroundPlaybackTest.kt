package com.tensei.ytxtube

import android.content.Intent
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local media fixture proves WebView/service behavior, independently of YouTube extraction. */
@RunWith(AndroidJUnit4::class)
class WebBackgroundPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun js(view: WebView, script: String): String {
        val done = CountDownLatch(1)
        var value = ""
        main { view.evaluateJavascript(script) { value = it; done.countDown() } }
        assertTrue("JavaScript response timed out", done.await(10, TimeUnit.SECONDS))
        return value
    }
    private fun await(message: String, seconds: Int = 15, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
        while (System.nanoTime() < until) {
            if (condition()) return
            Thread.sleep(250)
        }
        fail(message)
    }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }

    @Test fun backgroundButtonKeepsMediaPlayingPastOneMinuteAndNotificationControlsWork() {
        shell("pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var view: WebView
        val loaded = CountDownLatch(1)
        try {
            val wav = fixtureWav()
            scenario.onActivity { activity ->
                WebPlaybackSession.stop()
                view = BackgroundPlaybackWebView(activity.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false // Only for the local fixture.
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() }
                    }
                }
                WebPlaybackSession.view = view
                WebPlaybackSession.videoId = "fixture"
                WebPlaybackSession.attached = true
                activity.addContentView(view, ViewGroup.LayoutParams(-1, -1))
                view.loadDataWithBaseURL("https://m.youtube.com/", """
                    <html><body><video id="v" controls src="data:audio/wav;base64,$wav"></video></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            assertTrue("Fixture page did not load", loaded.await(15, TimeUnit.SECONDS))

            // The real button callback must reject a paused/unstarted video.
            val rejected = CountDownLatch(1)
            main { startWebBackground(view.context, view,
                onStarted = { fail("Unstarted media must not enable background mode") },
                onError = { rejected.countDown() }) }
            assertTrue(rejected.await(10, TimeUnit.SECONDS))
            main { assertFalse(WebPlaybackSession.background) }

            js(view, "document.querySelector('video').play().catch(e => window.fixtureError=String(e));")
            await("Fixture did not start: ${js(view, "window.fixtureError || ''")}") {
                js(view, "!document.querySelector('video').paused && document.querySelector('video').currentTime > 0") == "true"
            }
            val started = CountDownLatch(1)
            var error: String? = null
            main { startWebBackground(view.context, view,
                onStarted = {
                    (view.parent as? ViewGroup)?.removeView(view)
                    WebPlaybackSession.attached = false
                    started.countDown()
                }, onError = { error = it; started.countDown() }) }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            assertNull(error)
            await("Foreground service did not start") {
                var running = false
                main { running = WebPlaybackSession.service != null }
                running
            }
            shell("input keyevent KEYCODE_HOME")
            shell("input keyevent KEYCODE_SLEEP")
            // Require real media progress past the reported 59-second boundary, screen off.
            await("Web media stopped before 60 seconds with screen off", 80) {
                js(view, "document.querySelector('video').currentTime > 62 && !document.querySelector('video').paused") == "true"
            }
            main { view.context.startService(Intent(view.context, WebPlaybackService::class.java).setAction("pause")) }
            await("Notification Pause did not pause media") { js(view, "document.querySelector('video').paused") == "true" }
            main { view.context.startService(Intent(view.context, WebPlaybackService::class.java).setAction("play")) }
            await("Notification Play did not resume media") { js(view, "!document.querySelector('video').paused") == "true" }
            main { view.context.startService(Intent(view.context, WebPlaybackService::class.java).setAction("stop")) }
            await("Notification Stop did not release the web session") {
                var stopped = false
                main { stopped = WebPlaybackSession.view == null && !WebPlaybackSession.background }
                stopped
            }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            main { WebPlaybackSession.attached = false; WebPlaybackSession.stop(); WebPlaybackSession.destroy() }
            scenario.close()
        }
    }

    /** 90 seconds of low-volume PCM, generated in memory; no external network or files. */
    private fun fixtureWav(): String {
        val rate = 8000
        val samples = rate * 90
        val dataBytes = samples * 2
        val data = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVEfmt ".toByteArray())
        data.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(dataBytes)
        repeat(samples) { data.putShort((kotlin.math.sin(2.0 * Math.PI * 220 * it / rate) * 300).toInt().toShort()) }
        return Base64.encodeToString(data.array(), Base64.NO_WRAP)
    }
}
