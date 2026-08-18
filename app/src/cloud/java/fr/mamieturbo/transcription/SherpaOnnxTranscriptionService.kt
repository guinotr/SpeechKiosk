package fr.mamieturbo.transcription

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Lightweight implementation used by the public cloud-only build. */
class SherpaOnnxTranscriptionService(@Suppress("UNUSED_PARAMETER") context: Context) : RealtimeTranscriptionService {
    private val mutableEvents = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 1)
    override val events: SharedFlow<TranscriptionEvent> = mutableEvents

    fun isModelAvailable() = false
    fun configure(@Suppress("UNUSED_PARAMETER") settings: LocalTranscriptionSettings) = Unit
    fun engineStats() = LocalEngineStats()

    override suspend fun start() {
        mutableEvents.emit(TranscriptionEvent.Error("Le moteur local n'est pas inclus dans cette version."))
    }

    override fun sendAudio(pcm16: ByteArray, length: Int) = false
    override suspend fun stop() = Unit
}
