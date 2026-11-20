package com.tensei.freetube

import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TikTokGuestDeviceTest {
    @Test fun officialGuestSearchReturnsPublicProfile() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var view: WebView
        var state = "no result"
        var networkError: String? = null
        try {
            scenario.onActivity { activity ->
                view = BackgroundPlaybackWebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                            if (r.isForMainFrame) networkError = e.description.toString()
                        }
                    }
                }
                activity.addContentView(view, ViewGroup.LayoutParams(-1, -1))
                view.loadUrl("https://www.tiktok.com/search/user?q=tiktok")
            }
            for (attempt in 1..25) {
                val done = CountDownLatch(1)
                scenario.onActivity {
                    view.evaluateJavascript("""
                        (() => {
                            const profile = Array.from(document.links).some(a => /^\/@tiktok\/?$/i.test(a.pathname));
                            return profile ? 'profile found' : document.body?.innerText?.slice(0,180);
                        })()
                    """.trimIndent()) { result -> state = result; done.countDown() }
                }
                assertTrue("WebView stopped responding", done.await(5, TimeUnit.SECONDS))
                if (state == "\"profile found\"" || networkError != null) break
                Thread.sleep(1000)
            }
            assertTrue("TikTok guest search: ${networkError ?: state}", state == "\"profile found\"")
        } finally {
            scenario.onActivity {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            scenario.close()
        }
    }
}
