package fr.mamieturbo.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalEngineStatsTest {
    @Test fun realtimeFactorBelowOneMeansProcessingKeepsUp() {
        val stats = LocalEngineStats(processedAudioMillis = 10_000, processingMillis = 4_000)
        assertEquals(0.4, stats.realtimeFactor, 0.001)
    }

    @Test fun realtimeFactorIsZeroBeforeAudioArrives() {
        assertEquals(0.0, LocalEngineStats().realtimeFactor, 0.0)
    }
}
