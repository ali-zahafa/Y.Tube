package com.tensei.ytxtube

import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object LiveVideoTranslation {
    enum class Status { Off, Connecting, Listening, Error }

    private const val ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private const val MAX_QUEUE_BYTES = 128 * 1024L
    private val main = Handler(Looper.getMainLooper())
    private val client by lazy { Net.client.newBuilder().pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build() }
    private var socket: WebSocket? = null
    private var key: String? = null
    private var targetLanguage = "ar"
    private var resumeHandle: String? = null
    private var retries = 0
    private var connection = 0
    private var clearCaption: Runnable? = null
    private var newCaption = true

    @Volatile private var audioSocket: WebSocket? = null
    @Volatile internal var captureGeneration = 0
        private set
    @Volatile internal var playing = false

    internal val canCapture: Boolean get() = playing && audioSocket != null

    var status by mutableStateOf(Status.Off)
        private set
    var caption by mutableStateOf("")
        private set
    var failureCode by mutableStateOf<Int?>(null)
        private set

    fun start(apiKey: String, language: String) {
        stop()
        key = apiKey
        targetLanguage = language
        retries = 0
        connect()
    }

    fun stop() {
        connection++
        audioSocket = null
        captureGeneration++
        socket?.cancel()
        socket = null
        key = null
        resumeHandle = null
        clearText()
        failureCode = null
        status = Status.Off
    }

    fun seek() {
        if (key == null) return
        clearText()
        resumeHandle = null
        retries = 0
        connect()
    }

    private fun clearText() {
        clearCaption?.let(main::removeCallbacks)
        clearCaption = null
        caption = ""
        newCaption = true
    }

    private fun connect() {
        val apiKey = key ?: return
        val id = ++connection
        audioSocket = null
        captureGeneration++
        socket?.cancel()
        status = Status.Connecting
        val resumption = JSONObject().apply { resumeHandle?.let { put("handle", it) } }
        val setup = JSONObject().put("setup", JSONObject()
            .put("model", "models/gemini-3.5-live-translate-preview")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("outputAudioTranscription", JSONObject())
                .put("translationConfig", JSONObject().put("targetLanguageCode", targetLanguage)))
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", resumption))
        val request = Request.Builder().url(ENDPOINT).header("x-goog-api-key", apiKey).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                main.post {
                    if (id != connection || key == null) return@post
                    message.optJSONObject("error")?.let {
                        reconnect(id, it.optInt("code").takeIf { code -> code != 0 })
                        return@post
                    }
                    if (message.has("setupComplete")) {
                        audioSocket = webSocket
                        status = Status.Listening
                    }
                    message.optJSONObject("sessionResumptionUpdate")?.let {
                        resumeHandle = it.optString("newHandle").takeIf { handle -> handle.isNotBlank() && it.optBoolean("resumable") }
                    }
                    if (message.has("goAway")) {
                        reconnect(id, null)
                        return@post
                    }
                    val content = message.optJSONObject("serverContent") ?: return@post
                    val transcript = content.optJSONObject("outputTranscription")
                    val words = transcript?.optString("text").orEmpty()
                    if (words.isNotBlank() && playing) {
                        retries = 0
                        caption = (if (newCaption) words else caption + words).takeLast(240)
                        newCaption = transcript?.optBoolean("finished") == true
                        clearCaption?.let(main::removeCallbacks)
                        clearCaption = Runnable { clearText() }.also { main.postDelayed(it, 4_500) }
                    }
                    if (content.optBoolean("turnComplete")) newCaption = true
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { reconnect(id, response?.code) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                main.post { reconnect(id, code) }
            }
        })
        main.postDelayed({
            if (id == connection && status == Status.Connecting) reconnect(id, null)
        }, 20_000)
    }

    private fun reconnect(id: Int, code: Int?) {
        if (id != connection || key == null) return
        val next = ++connection
        audioSocket = null
        socket?.cancel()
        socket = null
        if (code in setOf(400, 401, 403, 404, 429, 1008) || retries >= 3) {
            stop()
            failureCode = code
            status = Status.Error
            return
        }
        retries++
        status = Status.Connecting
        main.postDelayed({ if (next == connection) connect() }, 500L * retries)
    }

    fun sendAudio(data: ByteArray, generation: Int) {
        val ws = audioSocket ?: return
        if (!playing || generation != captureGeneration || ws.queueSize() > MAX_QUEUE_BYTES) return
        ws.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
            .put("mimeType", "audio/pcm;rate=16000")
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP)))).toString())
    }
}

@UnstableApi
internal class TranslationAudioProcessor : BaseAudioProcessor() {
    private val chunk = ByteArray(3_200)
    private var offset = 0
    private var phase = 0
    private var captureGeneration = -1

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.sampleRate >= 16_000 && inputAudioFormat.channelCount > 0
        ) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val generation = LiveVideoTranslation.captureGeneration
        if (generation != captureGeneration) {
            captureGeneration = generation
            offset = 0
            phase = 0
        }
        if (LiveVideoTranslation.canCapture) {
            val pcm = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val channels = inputAudioFormat.channelCount
            while (pcm.remaining() >= channels * 2) {
                var sample = 0
                repeat(channels) { sample += pcm.short.toInt() }
                phase += 16_000
                if (phase < inputAudioFormat.sampleRate) continue
                phase -= inputAudioFormat.sampleRate
                val mono = sample / channels
                chunk[offset++] = mono.toByte()
                chunk[offset++] = (mono shr 8).toByte()
                if (offset == chunk.size) {
                    LiveVideoTranslation.sendAudio(chunk, generation)
                    offset = 0
                }
            }
        } else {
            offset = 0
            phase = 0
        }
        replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
    }
}
