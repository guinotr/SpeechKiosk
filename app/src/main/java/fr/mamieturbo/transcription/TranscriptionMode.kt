package fr.mamieturbo.transcription

enum class TranscriptionMode { OPENAI, LOCAL }

data class LocalTranscriptionSettings(
    val numThreads: Int = 2,
    val endpointSilenceSeconds: Float = 1.2f
)

data class LocalEngineStats(
    val initializationMillis: Long = 0,
    val processedAudioMillis: Long = 0,
    val processingMillis: Long = 0,
    val droppedChunks: Long = 0
) {
    val realtimeFactor: Double
        get() = if (processedAudioMillis == 0L) 0.0 else processingMillis.toDouble() / processedAudioMillis
}
