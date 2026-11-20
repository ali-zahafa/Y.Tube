package com.tensei.freetube

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TikTokNavigationTest {
    private fun findPage(view: View): WebView? {
        if (view is WebView && view.isShown) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findPage(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test fun selectedPublicVideoAdvancesInPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            device.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            requireNotNull(device.wait(Until.findObject(By.text("TikTok")), 20000)).click()
            val field = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15000))
            field.text = "https://www.tiktok.com/@scout2015/video/6718335390845095173"
            requireNotNull(device.wait(Until.findObject(By.text("Search")), 10000)).click()
            val deadline = android.os.SystemClock.elapsedRealtime() + 45000
            var firstTime: Double? = null
            var progress = 0.0
            var diagnostic = ""
            while (progress < 1.0 && android.os.SystemClock.elapsedRealtime() < deadline) {
                val done = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity {
                    val view = requireNotNull(findPage(it.window.decorView))
                    view.evaluateJavascript("""(() => {
                        const v = document.querySelector('video');
                        if (!v) return document.body?.innerText?.slice(0,160) || 'no video element';
                        return v.paused ? 'paused; readyState=' + v.readyState : v.currentTime;
                    })()""") { value ->
                        diagnostic = value
                        value.toDoubleOrNull()?.let { time ->
                            val start = firstTime ?: time
                            firstTime = start
                            progress = time - start
                        }
                        done.countDown()
                    }
                }
                assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (progress < 1.0) Thread.sleep(500)
            }
            assertTrue("Public TikTok video did not advance: $diagnostic", progress >= 1.0)
        }
    }

    @Test fun openTabTypeAndSearch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            device.executeShellCommand("pm grant ${instrumentation.targetContext.packageName} android.permission.POST_NOTIFICATIONS")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val tab = requireNotNull(device.wait(Until.findObject(By.text("TikTok")), 20000))
            tab.click()
            val field = requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15000))
            assertTrue("Search field has no screen area", !field.visibleBounds.isEmpty)
            scenario.onActivity {
                val web = requireNotNull(findPage(it.window.decorView))
                assertTrue("TikTok homepage was never requested", web.originalUrl?.startsWith("https://www.tiktok.com/") == true || web.url?.startsWith("https://www.tiktok.com/") == true)
            }
            field.text = "Cooking"
            val accounts = requireNotNull(device.wait(Until.findObject(By.text("Search")), 10000))
            accounts.click()
            val until = android.os.SystemClock.elapsedRealtime() + 10000
            var requested = false
            while (!requested && android.os.SystemClock.elapsedRealtime() < until) {
                scenario.onActivity {
                    val web = findPage(it.window.decorView)
                    requested = listOf(web?.originalUrl, web?.url).any { url -> url == TikTokGuest.destination("Cooking") }
                }
                if (!requested) Thread.sleep(150)
            }
            assertTrue("Search did not open the entered account query", requested)
            var result: String? = null
            val resultDeadline = android.os.SystemClock.elapsedRealtime() + 30000
            while (result == null && android.os.SystemClock.elapsedRealtime() < resultDeadline) {
                val done = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity {
                    val web = requireNotNull(findPage(it.window.decorView))
                    web.evaluateJavascript("JSON.stringify(Array.from(document.querySelectorAll('a.result__a'), a => a.href))") { json ->
                        val raw = org.json.JSONTokener(json).nextValue() as? String
                        val links = org.json.JSONArray(raw ?: "[]")
                        for (i in 0 until links.length()) {
                            val href = links.getString(i)
                            if (TikTokGuest.navigation(href)?.let(TikTokGuest::allowed) == true) {
                                result = href
                                break
                            }
                        }
                        done.countDown()
                    }
                }
                assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (result == null) Thread.sleep(500)
            }
            val link = requireNotNull(result) { "Live search returned no public TikTok result for Cooking" }
            scenario.onActivity {
                findPage(it.window.decorView)?.evaluateJavascript("document.querySelectorAll('a.result__a').forEach(a => { if(a.href === " + org.json.JSONObject.quote(link) + ") a.click() })", null)
            }
            var onTikTok = false
            val openDeadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (!onTikTok && android.os.SystemClock.elapsedRealtime() < openDeadline) {
                scenario.onActivity { onTikTok = findPage(it.window.decorView)?.url?.let(TikTokGuest::allowed) == true }
                if (!onTikTok) Thread.sleep(200)
            }
            assertTrue("Live result did not open on TikTok", onTikTok)
        }
    }
}
