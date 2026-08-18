package fr.mamieturbo.transcription

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class AudioGateStats(val capturedChunks: Long, val transmittedChunks: Long) {
    val capturedSeconds: Long get() = capturedChunks / 10L
    val transmittedSeconds: Long get() = transmittedChunks / 10L
    val savedPercent: Long get() = if (capturedChunks == 0L) 0L else
        ((capturedChunks - transmittedChunks).coerceAtLeast(0L) * 100L / capturedChunks)
}

class OpenAIRealtimeTranscriptionService : RealtimeTranscriptionService {
    companion object { private const val TAG = "SpeechKioskRealtime" }
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val mutableEvents = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TranscriptionEvent> = mutableEvents
    private val lifecycleMutex = Mutex()
    @Volatile private var desired = false
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    private val speechGate = SpeechGate()
    @Volatile private var capturedChunks = 0L
    @Volatile private var transmittedChunks = 0L
    @Volatile private var apiKey = ""
    @Volatile private var language = "fr"

    fun configureCredentials(apiKey: String, language: String) {
        this.apiKey = apiKey.trim()
        this.language = language.ifBlank { "fr" }
    }

    fun configureSilenceFiltering(enabled: Boolean, sensitivity: SpeechSensitivity) {
        speechGate.configure(enabled, sensitivity)
        Log.i(TAG, "Silence filtering ${if (enabled) "enabled" else "disabled"}, sensitivity=${sensitivity.name}")
    }

    fun audioGateStats() = AudioGateStats(capturedChunks, transmittedChunks)

    override suspend fun start() = lifecycleMutex.withLock {
        if (desired) return
        desired = true
        var backoff = 1_000L
        while (desired) {
            mutableEvents.emit(TranscriptionEvent.Connecting)
            val closed = connectOnce()
            if (!desired) break
            mutableEvents.emit(TranscriptionEvent.Disconnected(closed))
            delay(backoff)
            backoff = min(backoff * 2, 16_000L)
        }
    }

    private suspend fun connectOnce(): String {
        if (apiKey.isBlank()) {
            mutableEvents.emit(TranscriptionEvent.Error("Clé OpenAI absente"))
            delay(5_000)
            return "Clé absente"
        }
        val completion = CompletableDeferred<String>()
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?intent=transcription")
            .header("Authorization", "Bearer $apiKey")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Realtime WebSocket connected")
                socket = webSocket
                webSocket.send(sessionConfiguration())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                speechGate.reset()
                completion.complete("$code $reason")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = t.message ?: "Connexion interrompue"
                Log.e(TAG, "Realtime WebSocket failure: $reason", t)
                ready = false
                speechGate.reset()
                mutableEvents.tryEmit(TranscriptionEvent.Error(reason))
                completion.complete(reason)
            }
        })
        return completion.await()
    }

    private fun sessionConfiguration() = JSONObject().apply {
        put("type", "session.update")
        put("session", JSONObject().apply {
            put("type", "transcription")
            put("audio", JSONObject().put("input", JSONObject().apply {
                put("format", JSONObject().put("type", "audio/pcm").put("rate", 24_000))
                put("transcription", JSONObject()
                    .put("model", "gpt-live-transcribe")
                    .put("languages", org.json.JSONArray().put(language))
                    .put("prompt", "Transcribe faithfully in the selected language ($language). Do not translate.")
                    .put("delay", "low"))
                // Client-side VAD keeps long silences off the network while this
                // explicit commit mode preserves progressive transcript deltas.
                put("turn_detection", JSONObject.NULL)
            }))
        })
    }.toString()

    private fun handleMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "session.created" -> mutableEvents.tryEmit(TranscriptionEvent.Connected)
            "session.updated" -> { ready = true; mutableEvents.tryEmit(TranscriptionEvent.Listening) }
            "conversation.item.input_audio_transcription.delta" -> mutableEvents.tryEmit(
                TranscriptionEvent.PartialTranscript(json.optString("item_id"), json.optString("delta")))
            "conversation.item.input_audio_transcription.completed" -> mutableEvents.tryEmit(
                TranscriptionEvent.FinalTranscript(json.optString("item_id"), json.optString("transcript")))
            "error" -> {
                val message = json.optJSONObject("error")?.optString("message") ?: "Erreur OpenAI"
                Log.e(TAG, "OpenAI error: $message")
                mutableEvents.tryEmit(TranscriptionEvent.Error(message))
            }
        }
    }

    override fun sendAudio(pcm16: ByteArray, length: Int): Boolean {
        val target = socket
        if (!ready || target == null) return false // discard while offline; never queue old audio
        capturedChunks++
        val decision = speechGate.process(pcm16, length)
        var sent = true
        decision.audioChunks.forEach { chunk ->
            val encoded = Base64.encodeToString(chunk, Base64.NO_WRAP)
            sent = target.send(JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", encoded)
                .toString()) && sent
            transmittedChunks++
        }
        if (decision.commit) {
            sent = target.send(JSONObject().put("type", "input_audio_buffer.commit").toString()) && sent
        }
        if (capturedChunks % 600L == 0L) {
            val savedPercent = if (capturedChunks == 0L) 0 else
                ((capturedChunks - transmittedChunks).coerceAtLeast(0L) * 100L / capturedChunks)
            Log.i(TAG, "Audio gate: $transmittedChunks/$capturedChunks chunks sent ($savedPercent% withheld)")
        }
        return sent
    }

    override suspend fun stop() = lifecycleMutex.withLock {
        desired = false
        ready = false
        speechGate.reset()
        capturedChunks = 0L
        transmittedChunks = 0L
        socket?.close(1000, "App paused"); socket = null
    }
}
