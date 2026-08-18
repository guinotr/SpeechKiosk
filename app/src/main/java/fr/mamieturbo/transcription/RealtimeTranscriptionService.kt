package fr.mamieturbo.transcription

import kotlinx.coroutines.flow.SharedFlow

interface RealtimeTranscriptionService {
    val events: SharedFlow<TranscriptionEvent>
    suspend fun start()
    suspend fun stop()
    fun sendAudio(pcm16: ByteArray, length: Int): Boolean
}
