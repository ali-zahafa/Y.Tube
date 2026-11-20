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
            val accounts = requireNotNull(device.wait(Until.findObject(By.text("Accounts")), 10000))
            accounts.click()
            val until = android.os.SystemClock.elapsedRealtime() + 10000
            var requested = false
            while (!requested && android.os.SystemClock.elapsedRealtime() < until) {
                scenario.onActivity {
                    val web = findPage(it.window.decorView)
                    requested = listOf(web?.originalUrl, web?.url).any { url -> url?.contains("/search/user?q=Cooking") == true }
                }
                if (!requested) Thread.sleep(150)
            }
            assertTrue("Search did not open the entered account query", requested)
        }
    }
}
