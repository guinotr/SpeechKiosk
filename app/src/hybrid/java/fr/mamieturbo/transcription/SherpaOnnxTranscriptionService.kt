package fr.mamieturbo.transcription

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class SherpaOnnxTranscriptionService(context: Context) : RealtimeTranscriptionService {
    companion object {
        private const val TAG = "MamieTurboLocal"
        private const val MODEL_DIR = "sherpa-onnx-streaming-zipformer-fr-2023-04-14"
        private const val INPUT_SAMPLE_RATE = 24_000
    }

    private val assets = context.applicationContext.assets
    private val mutableEvents = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TranscriptionEvent> = mutableEvents
    @Volatile private var desired = false
    @Volatile private var audioChannel: Channel<ByteArray>? = null
    @Volatile private var settings = LocalTranscriptionSettings()
    @Volatile private var stats = LocalEngineStats()
    private val droppedChunks = AtomicLong(0)

    fun isModelAvailable(): Boolean = runCatching {
        val files = assets.list(MODEL_DIR)?.toSet().orEmpty()
        files.containsAll(setOf(
            "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
            "decoder-epoch-29-avg-9-with-averaged-model.onnx",
            "tokens.txt"
        )) && files.any { it == "joiner-epoch-29-avg-9-with-averaged-model.onnx" || it == "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx" }
    }.getOrDefault(false)

    fun configure(settings: LocalTranscriptionSettings) {
        this.settings = settings.copy(numThreads = settings.numThreads.coerceIn(1, 4))
    }

    fun engineStats(): LocalEngineStats = stats.copy(droppedChunks = droppedChunks.get())

    override suspend fun start() {
        if (desired) return
        if (!isModelAvailable()) {
            mutableEvents.emit(TranscriptionEvent.Error("Modèle local absent. Lancez le script de téléchargement puis recompilez la variante hybrid."))
            return
        }
        desired = true
        droppedChunks.set(0)
        stats = LocalEngineStats()
        mutableEvents.emit(TranscriptionEvent.Connecting)
        val channel = Channel<ByteArray>(
            capacity = 30,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { droppedChunks.incrementAndGet() }
        )
        audioChannel = channel

        withContext(Dispatchers.Default) {
            var recognizer: OnlineRecognizer? = null
            var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
            try {
                val startedAt = System.currentTimeMillis()
                recognizer = OnlineRecognizer(assetManager = assets, config = recognizerConfig(settings))
                stream = recognizer.createStream()
                stats = stats.copy(initializationMillis = System.currentTimeMillis() - startedAt)
                mutableEvents.tryEmit(TranscriptionEvent.Connected)
                mutableEvents.tryEmit(TranscriptionEvent.Listening)
                processAudio(channel, recognizer, stream)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Local transcription failed", error)
                mutableEvents.tryEmit(TranscriptionEvent.Error("Mode local indisponible : ${error.message ?: error.javaClass.simpleName}"))
            } finally {
                stream?.release()
                recognizer?.release()
                audioChannel = null
                desired = false
            }
        }
    }

    private suspend fun processAudio(
        channel: Channel<ByteArray>,
        recognizer: OnlineRecognizer,
        stream: com.k2fsa.sherpa.onnx.OnlineStream
    ) {
        var itemIndex = 0L
        var lastPartial = ""
        for (bytes in channel) {
            if (!desired) break
            val processingStarted = System.nanoTime()
            val samples = pcm16ToFloat(bytes)
            stream.acceptWaveform(samples, INPUT_SAMPLE_RATE)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            val itemId = "local-$itemIndex"
            if (text != lastPartial) {
                mutableEvents.tryEmit(TranscriptionEvent.PartialTranscript(itemId, text, replacesPrevious = true))
                lastPartial = text
            }
            if (recognizer.isEndpoint(stream)) {
                if (text.isNotBlank()) mutableEvents.tryEmit(TranscriptionEvent.FinalTranscript(itemId, text))
                recognizer.reset(stream)
                lastPartial = ""
                itemIndex++
            }
            val processingMillis = (System.nanoTime() - processingStarted) / 1_000_000L
            val audioMillis = bytes.size * 1_000L / (INPUT_SAMPLE_RATE * 2L)
            stats = stats.copy(
                processedAudioMillis = stats.processedAudioMillis + audioMillis,
                processingMillis = stats.processingMillis + processingMillis,
                droppedChunks = droppedChunks.get()
            )
        }
    }

    override fun sendAudio(pcm16: ByteArray, length: Int): Boolean {
        if (!desired || length <= 0) return false
        return audioChannel?.trySend(pcm16.copyOf(length))?.isSuccess == true
    }

    override suspend fun stop() {
        desired = false
        audioChannel?.close()
    }

    private fun recognizerConfig(settings: LocalTranscriptionSettings) = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$MODEL_DIR/encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
                decoder = "$MODEL_DIR/decoder-epoch-29-avg-9-with-averaged-model.onnx",
                joiner = "$MODEL_DIR/${joinerFileName()}"
            ),
            tokens = "$MODEL_DIR/tokens.txt",
            numThreads = settings.numThreads,
            debug = false,
            provider = "cpu",
            modelType = "zipformer"
        ),
        endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0f),
            rule2 = EndpointRule(true, settings.endpointSilenceSeconds, 0f),
            rule3 = EndpointRule(false, 0f, 20f)
        ),
        enableEndpoint = true,
        decodingMethod = "greedy_search",
        maxActivePaths = 4
    )

    private fun joinerFileName(): String {
        val files = assets.list(MODEL_DIR)?.toSet().orEmpty()
        return if ("joiner-epoch-29-avg-9-with-averaged-model.int8.onnx" in files) {
            "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx"
        } else {
            "joiner-epoch-29-avg-9-with-averaged-model.onnx"
        }
    }

    private fun pcm16ToFloat(bytes: ByteArray): FloatArray {
        val samples = FloatArray(bytes.size / 2)
        var byteIndex = 0
        for (sampleIndex in samples.indices) {
            val value = (bytes[byteIndex].toInt() and 0xff) or (bytes[byteIndex + 1].toInt() shl 8)
            samples[sampleIndex] = value.toShort() / 32768.0f
            byteIndex += 2
        }
        return samples
    }
}
