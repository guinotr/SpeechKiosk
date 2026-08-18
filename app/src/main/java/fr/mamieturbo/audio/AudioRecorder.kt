package fr.mamieturbo.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioRecorder(private val context: Context) {
    companion object { const val SAMPLE_RATE = 24_000 }
    private var recorder: AudioRecord? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope, onAudio: (ByteArray, Int) -> Unit, onError: (String) -> Unit) {
        if (job != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Permission microphone refusée")
            return
        }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) { onError("Micro indisponible"); return }
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 2, 9_600)
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release(); onError("Micro indisponible"); return
        }
        recorder = audioRecord
        audioRecord.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4_800) // 100 ms of mono 24 kHz PCM16
            try {
                while (isActive) {
                    val read = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) onAudio(buffer, read)
                    else if (read < 0) onError("Erreur microphone ($read)")
                }
            } finally { runCatching { audioRecord.stop() }; audioRecord.release() }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { recorder?.stop() }; recorder = null
    }
}
