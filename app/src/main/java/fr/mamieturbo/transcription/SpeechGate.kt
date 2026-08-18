package fr.mamieturbo.transcription

import kotlin.math.abs
import kotlin.math.max

internal data class SpeechGateDecision(
    val audioChunks: List<ByteArray>,
    val commit: Boolean = false
)

enum class SpeechSensitivity(val minimumAmplitude: Int, val noiseMultiplier: Double) {
    LOW(400, 4.0),
    NORMAL(250, 3.0),
    HIGH(140, 2.0)
}

/**
 * Local energy gate for 100 ms PCM16 chunks.
 *
 * Long silences stay on the device. A short pre-roll is retained so the first
 * phoneme is not lost, then trailing silence is sent before committing the turn.
 */
internal class SpeechGate(
    enabled: Boolean = true,
    sensitivity: SpeechSensitivity = SpeechSensitivity.NORMAL,
    private val preRollChunks: Int = 4,
    private val trailingSilenceChunks: Int = 7
) {
    private val preRoll = ArrayDeque<ByteArray>()
    private var speaking = false
    private var silentChunks = 0
    private var noiseFloor = 40.0
    private var enabled = enabled
    private var sensitivity = sensitivity

    @Synchronized
    fun process(pcm16: ByteArray, length: Int): SpeechGateDecision {
        if (length < 2) return SpeechGateDecision(emptyList())
        val chunk = pcm16.copyOf(length)
        val amplitude = averageAmplitude(chunk)
        val startThreshold = max(
            sensitivity.minimumAmplitude.toDouble(),
            noiseFloor * sensitivity.noiseMultiplier
        )

        if (!speaking) {
            if (amplitude < startThreshold) {
                noiseFloor = noiseFloor * 0.95 + amplitude * 0.05
                if (enabled) retainPreRoll(chunk)
                return SpeechGateDecision(if (enabled) emptyList() else listOf(chunk))
            }

            speaking = true
            silentChunks = 0
            val chunks = if (enabled) preRoll.toMutableList() else mutableListOf()
            preRoll.clear()
            chunks += chunk
            return SpeechGateDecision(chunks)
        }

        // A lower continuation threshold prevents chopping quiet syllables.
        val continuationThreshold = startThreshold * 0.55
        if (amplitude >= continuationThreshold) silentChunks = 0 else silentChunks++

        val shouldCommit = silentChunks >= trailingSilenceChunks
        if (shouldCommit) {
            speaking = false
            silentChunks = 0
            preRoll.clear()
        }
        return SpeechGateDecision(listOf(chunk), shouldCommit)
    }

    @Synchronized
    fun configure(enabled: Boolean, sensitivity: SpeechSensitivity) {
        this.enabled = enabled
        this.sensitivity = sensitivity
        if (!enabled) preRoll.clear()
    }

    @Synchronized
    fun reset() {
        preRoll.clear()
        speaking = false
        silentChunks = 0
        noiseFloor = 40.0
    }

    private fun retainPreRoll(chunk: ByteArray) {
        if (preRollChunks <= 0) return
        preRoll.addLast(chunk)
        while (preRoll.size > preRollChunks) preRoll.removeFirst()
    }

    private fun averageAmplitude(bytes: ByteArray): Int {
        var sum = 0L
        var samples = 0
        var index = 0
        while (index + 1 < bytes.size) {
            val sample = (bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)
            sum += abs(sample.toShort().toInt())
            samples++
            index += 2
        }
        return if (samples == 0) 0 else (sum / samples).toInt()
    }
}
