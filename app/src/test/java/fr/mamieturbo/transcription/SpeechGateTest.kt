package fr.mamieturbo.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {
    private fun pcm(amplitude: Short): ByteArray = ByteArray(20).also { bytes ->
        repeat(10) { index ->
            bytes[index * 2] = amplitude.toInt().toByte()
            bytes[index * 2 + 1] = (amplitude.toInt() shr 8).toByte()
        }
    }

    @Test
    fun longSilenceIsKeptLocal() {
        val gate = SpeechGate()
        repeat(100) {
            val decision = gate.process(pcm(20), 20)
            assertTrue(decision.audioChunks.isEmpty())
            assertFalse(decision.commit)
        }
    }

    @Test
    fun speechFlushesOnlyBoundedPreRoll() {
        val gate = SpeechGate(preRollChunks = 4)
        repeat(20) { gate.process(pcm(20), 20) }

        val decision = gate.process(pcm(1_000), 20)

        assertEquals(5, decision.audioChunks.size)
        assertFalse(decision.commit)
    }

    @Test
    fun sevenSilentChunksCommitTheTurn() {
        val gate = SpeechGate(trailingSilenceChunks = 7)
        gate.process(pcm(1_000), 20)
        repeat(6) { assertFalse(gate.process(pcm(0), 20).commit) }
        assertTrue(gate.process(pcm(0), 20).commit)
        assertTrue(gate.process(pcm(0), 20).audioChunks.isEmpty())
    }

    @Test
    fun quieterSyllableDoesNotInterruptActiveSpeech() {
        val gate = SpeechGate()
        gate.process(pcm(1_000), 20)

        repeat(20) {
            val decision = gate.process(pcm(180), 20)
            assertFalse(decision.commit)
            assertEquals(1, decision.audioChunks.size)
        }
    }

    @Test
    fun resetRemovesBufferedAudio() {
        val gate = SpeechGate(preRollChunks = 4)
        repeat(4) { gate.process(pcm(20), 20) }
        gate.reset()

        assertEquals(1, gate.process(pcm(1_000), 20).audioChunks.size)
    }

    @Test
    fun disabledFilterSendsEverySilentChunk() {
        val gate = SpeechGate(enabled = false)
        repeat(20) { assertEquals(1, gate.process(pcm(0), 20).audioChunks.size) }
    }

    @Test
    fun highSensitivityDetectsAQuietVoice() {
        val high = SpeechGate(sensitivity = SpeechSensitivity.HIGH, preRollChunks = 0)
        val normal = SpeechGate(sensitivity = SpeechSensitivity.NORMAL, preRollChunks = 0)
        assertEquals(1, high.process(pcm(180), 20).audioChunks.size)
        assertTrue(normal.process(pcm(180), 20).audioChunks.isEmpty())
    }

    @Test
    fun settingsCanChangeWithoutRestarting() {
        val gate = SpeechGate()
        assertTrue(gate.process(pcm(0), 20).audioChunks.isEmpty())
        gate.configure(enabled = false, sensitivity = SpeechSensitivity.LOW)
        assertEquals(1, gate.process(pcm(0), 20).audioChunks.size)
    }

    @Test
    fun audioStatsCalculateDurationsAndSavings() {
        val stats = AudioGateStats(capturedChunks = 1_200, transmittedChunks = 300)
        assertEquals(120, stats.capturedSeconds)
        assertEquals(30, stats.transmittedSeconds)
        assertEquals(75, stats.savedPercent)
    }
}
