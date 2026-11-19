package com.tensei.freetube.potoken

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.collection.ArrayMap
import com.tensei.freetube.BuildConfig
import com.tensei.freetube.Net
import java.time.Instant
import java.util.Collections
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.RequestBody.Companion.toRequestBody

class PoTokenWebView
private constructor(
  context: Context,

  private val continuation: CancellableContinuation<PoTokenWebView>,
) {
  private val webView = WebView(context)
  private val scope = MainScope()
  private val poTokenContinuations =
    Collections.synchronizedMap(ArrayMap<String, Continuation<String>>())
  private val exceptionHandler = CoroutineExceptionHandler { _, t ->
    onInitializationErrorCloseAndCancel(t)
  }
  private var closed = false
  private lateinit var expirationInstant: Instant


  init {
    val webViewSettings = webView.settings

    webViewSettings.javaScriptEnabled = true
    webViewSettings.userAgentString = USER_AGENT
    webViewSettings.blockNetworkLoads = true


    webView.addJavascriptInterface(this, JS_INTERFACE)

    webView.webChromeClient =
      object : WebChromeClient() {
        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
          val msg = m.message()
          when (m.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, "JS: $msg")
            ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "JS: $msg")
            else -> Log.d(TAG, "JS: $msg")
          }

          if (msg.contains("Uncaught")) {
            val fmt = "\"$msg\", source: ${m.sourceId()} (${m.lineNumber()})"
            val exception = BadWebViewException(fmt)
            Log.e(TAG, "This WebView implementation is broken: $fmt")

            onInitializationErrorCloseAndCancel(exception)
            popAllPoTokenContinuations().forEach { (_, cont) ->
              cont.resumeWithException(exception)
            }
          }
          return super.onConsoleMessage(m)
        }
      }
  }

  




  private fun loadHtmlAndObtainBotguard() {
    Log.d(TAG, "loadHtmlAndObtainBotguard() called")

    scope.launch(exceptionHandler) {
      val html =
        withContext(Dispatchers.IO) {
          webView.context.assets.open("po_token.html").bufferedReader().use { it.readText() }
        }


      val data = html.replaceFirst("</script>", "\n$JS_INTERFACE.downloadAndRunBotguard()</script>")
      webView.loadDataWithBaseURL("https://www.youtube.com", data, "text/html", "utf-8", null)
    }
  }

  



  @JavascriptInterface
  fun downloadAndRunBotguard() {
    Log.d(TAG, "downloadAndRunBotguard() called")

    makeBotguardServiceRequest(
      "https://www.youtube.com/api/jnn/v1/Create",
      "[ \"$REQUEST_KEY\" ]",
    ) { responseBody ->
      val parsedChallengeData = parseChallengeData(responseBody)
      webView.evaluateJavascript(
        """try {
                    data = $parsedChallengeData
                    runBotGuard(data).then(function (result) {
                        this.webPoSignalOutput = result.webPoSignalOutput
                        $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                    }, function (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
        null
      )
    }
  }

  



  @JavascriptInterface
  fun onJsInitializationError(error: String) {
    if (BuildConfig.DEBUG) {
      Log.e(TAG, "Initialization error from JavaScript: $error")
    }
    onInitializationErrorCloseAndCancel(buildExceptionForJsError(error))
  }

  



  @JavascriptInterface
  fun onRunBotguardResult(botguardResponse: String) {
    Log.d(TAG, "BotGuard challenge response received")
    makeBotguardServiceRequest(
      "https://www.youtube.com/api/jnn/v1/GenerateIT",
      "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
    ) { responseBody ->
      Log.d(TAG, "Integrity response received")
      try {
        val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)
        Log.d(
          TAG,
          "Integrity token lifetime: $expirationTimeInSeconds sec"
        )


        expirationInstant =
          PoTokenLifetime.expiresAt(Instant.now(), expirationTimeInSeconds)


        Log.d(TAG, "Evaluating createPoTokenMinter JavaScript...")
        webView.evaluateJavascript(
          """try {
                        console.log('[JS] Setting integrityToken and calling createPoTokenMinter...');
                        this.integrityToken = $integrityToken
                        console.log('[JS] integrityToken set, now calling createPoTokenMinter...');
                        createPoTokenMinter(webPoSignalOutput, integrityToken).then(function() {
                            console.log('[JS] createPoTokenMinter .then() resolved!');
                            $JS_INTERFACE.onMinterCreated()
                        }).catch(function(error) {
                            console.log('[JS] createPoTokenMinter .catch() error: ' + error);
                            $JS_INTERFACE.onJsInitializationError(error + "\n" + (error.stack || ''))
                        })
                    } catch (error) {
                        console.log('[JS] createPoTokenMinter SYNC error: ' + error);
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    }""",
          null
        )
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse integrity token data: ${e.message}", e)
        onInitializationErrorCloseAndCancel(
          PoTokenException("parseIntegrityTokenData failed: ${e.message}")
        )
      }
    }
  }


  @JavascriptInterface
  fun onMinterCreated() {
    Log.d(TAG, "poToken minter created successfully, initialization complete")
    scope.launch {
      if (!closed && continuation.isActive) continuation.resume(this@PoTokenWebView)
    }
  }



  suspend fun generatePoToken(identifier: String): String {
    return withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { cont ->
        // Each asynchronous callback needs its own key, even for the same video.
        val requestKey = java.util.UUID.randomUUID().toString()
        addPoTokenEmitter(requestKey, cont)
        cont.invokeOnCancellation { popPoTokenContinuation(requestKey) }
        webView.evaluateJavascript(
          """(function () { try {
                        const requestKey = "$requestKey";
                        const u8Identifier = ${stringToU8(identifier)};
                        obtainPoToken(u8Identifier).then(function(poTokenU8) {
                            const poTokenU8String = poTokenU8.join(",");
                            $JS_INTERFACE.onObtainPoTokenResult(requestKey, poTokenU8String);
                        }).catch(function(error) {
                            $JS_INTERFACE.onObtainPoTokenError(requestKey, error + "\n" + (error.stack || ''));
                        });
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError("$requestKey", error + "\n" + error.stack);
                    } })();""",
          null
        )
      }
    }
  }

  



  @JavascriptInterface
  fun onObtainPoTokenError(identifier: String, error: String) {
    if (BuildConfig.DEBUG) {
      Log.e(TAG, "obtainPoToken error from JavaScript: $error")
    }
    popPoTokenContinuation(identifier)?.resumeWithException(buildExceptionForJsError(error))
  }

  



  @JavascriptInterface
  fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {

    val poToken =
      try {
        u8ToBase64(poTokenU8)
      } catch (t: Throwable) {
        popPoTokenContinuation(identifier)?.resumeWithException(t)
        return
      }

    Log.d(TAG, "PoToken generated")
    popPoTokenContinuation(identifier)?.resume(poToken)
  }

  val isExpired: Boolean
    get() = Instant.now().isAfter(expirationInstant)



  private fun addPoTokenEmitter(identifier: String, continuation: Continuation<String>) {
    poTokenContinuations[identifier] = continuation
  }

  private fun popPoTokenContinuation(identifier: String): Continuation<String>? {
    return poTokenContinuations.remove(identifier)
  }

  private fun popAllPoTokenContinuations(): Map<String, Continuation<String>> {
    return synchronized(poTokenContinuations) {
      val result = poTokenContinuations.toMap()
      poTokenContinuations.clear()
      result
    }
  }



  private fun makeBotguardServiceRequest(
    url: String,
    data: String,
    handleResponseBody: (String) -> Unit,
  ) {
    scope.launch(exceptionHandler) {
      val requestBuilder =
        okhttp3.Request.Builder()
          .post(data.toRequestBody())
          .headers(
            mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json",
                "Content-Type" to "application/json+protobuf",
                "x-goog-api-key" to GOOGLE_API_KEY,
                "x-user-agent" to "grpc-web-javascript/0.1",
              )
              .toHeaders()
          )
          .url(url)
      val (httpCode, body) = withContext(Dispatchers.IO) {
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
          response.code to response.body?.string().orEmpty()
        }
      }
      if (httpCode != 200) {
        onInitializationErrorCloseAndCancel(PoTokenException("Invalid response code: $httpCode"))
      } else {
        handleResponseBody(body)
      }
    }
  }

  private fun onInitializationErrorCloseAndCancel(error: Throwable) {
    scope.launch {
      if (continuation.isActive) continuation.resumeWithException(error)
      close()
    }
  }

  @MainThread
  fun close() {
    if (closed) return
    closed = true
    scope.cancel()
    popAllPoTokenContinuations().values.forEach {
      it.resumeWithException(kotlinx.coroutines.CancellationException("PoToken generator closed"))
    }

    webView.clearHistory()

    webView.loadUrl("about:blank")

    webView.onPause()
    webView.removeAllViews()
    webView.destroy()
  }


  companion object {
    private const val TAG = "PoTokenWebView"
    private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    private const val USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
    private const val JS_INTERFACE = "PoTokenWebView"

    private val httpClient by lazy { Net.client }

    suspend fun getNewPoTokenGenerator(context: Context): PoTokenWebView {
      return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
          val potWv = PoTokenWebView(context, cont)
          cont.invokeOnCancellation {
            android.os.Handler(android.os.Looper.getMainLooper()).post { potWv.close() }
          }
          potWv.loadHtmlAndObtainBotguard()
        }
      }
    }
  }
}
