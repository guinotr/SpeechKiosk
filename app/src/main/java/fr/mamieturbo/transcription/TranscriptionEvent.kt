package fr.mamieturbo.transcription

sealed interface TranscriptionEvent {
    data object Connecting : TranscriptionEvent
    data object Connected : TranscriptionEvent
    data object Listening : TranscriptionEvent
    data class PartialTranscript(
        val itemId: String,
        val text: String,
        val replacesPrevious: Boolean = false
    ) : TranscriptionEvent
    data class FinalTranscript(val itemId: String, val text: String, val speakerId: String? = null) : TranscriptionEvent
    data class Disconnected(val reason: String? = null) : TranscriptionEvent
    data class Error(val message: String) : TranscriptionEvent
}
